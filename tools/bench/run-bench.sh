#!/usr/bin/env bash
# PocketIDE benchmark runner that survives a Codespace restart.
#
# A restart wipes /tmp. Worktrees and Gradle builds still live there (big, fast, disposable), but every report and transcript
# is copied to $BENCH_RESULTS (default /workspaces/bench-results, which survives) while it is written. After a restart,
# `resume` rebuilds the worktrees from the saved commit and patch and runs only the task runs that have no result yet.
#
#   run-bench.sh start [-n NAME] [-r REPEAT] [-t task1,task2] [-s SUITE] [-c COMMIT] [--no-wip] [--foreground] MODEL...
#       MODEL is provider:model (groq:llama-3.3-70b-versatile), or @cf/... for Cloudflare. Models run in parallel.
#       Uncommitted changes in the repo (tracked and new files) are included unless --no-wip. Returns at once; the run
#       goes on in the background. --foreground waits for the run (for a process supervisor).
#   run-bench.sh resume [--interrupted] [RUN]
#       RUN: a run folder or part of its name; default every unfinished run. --interrupted: only runs a restart cut off
#       (the .vscode task "PocketIDE: resume benchmarks" runs this when the Codespace opens).
#   run-bench.sh status [RUN]     default the latest run
#   run-bench.sh stop [RUN]
#   run-bench.sh save-tmp [DEST]  copy reports of runs started another way (/tmp/bench-wt-*) to DEST until they finish
#
# Keys and addresses come from the environment (POCKETIDE_BENCH_KEY_<PROVIDER>, POCKETIDE_BENCH_BASE_<PROVIDER>), or from the
# Codespace secrets file when the environment lacks them. They are never written to the run folder.
#
# Environment: BENCH_REPO, BENCH_RESULTS, BENCH_WT_ROOT, BENCH_JAVA_HOME (default JDK 17), BENCH_STAGGER (seconds between model starts, default 150),
# BENCH_XMX (Gradle heap, default 1536m), BENCH_FAKE=1 (no Gradle, no model: writes passing rows; for testing this script).
set -uo pipefail

REPO=${BENCH_REPO:-/workspaces/Test-Coder2.3}
RESULTS=${BENCH_RESULTS:-/workspaces/bench-results}
WT_ROOT=${BENCH_WT_ROOT:-/tmp/pocketide-bench-runs}
STAGGER=${BENCH_STAGGER:-150}
XMX=${BENCH_XMX:-1536m}
SELF=$(readlink -f "$0")
# The build needs JDK 17; the Codespace's default JAVA_HOME can be a newer JDK the Android plugin fails on ("25.0.5.0.2").
JDK17=/usr/lib/jvm/java-17-openjdk-amd64
export JAVA_HOME=${BENCH_JAVA_HOME:-$( [[ -x $JDK17/bin/java ]] && echo "$JDK17" || echo "${JAVA_HOME:-}" )}

die() { echo "run-bench: $*" >&2; exit 1; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

load_secrets() {
  local f=/workspaces/.codespaces/shared/.env line k
  if [[ -f $f ]]; then
    while IFS= read -r line; do
      case "$line" in
        POCKETIDE_BENCH_*=*) k=${line%%=*}; [[ -z $(printenv "$k") ]] && export "$line" ;;
      esac
    done < "$f"
  fi
  # the Cloudflare secret was saved with the account id wrapped in <...>
  if [[ -n ${POCKETIDE_BENCH_BASE_CLOUDFLARE:-} ]]; then
    POCKETIDE_BENCH_BASE_CLOUDFLARE=$(printf '%s' "$POCKETIDE_BENCH_BASE_CLOUDFLARE" | tr -d '<> ')
    export POCKETIDE_BENCH_BASE_CLOUDFLARE
  fi
}

spec_of() { case "$1" in @cf/*) printf 'cloudflare:%s' "$1" ;; *:*) printf '%s' "$1" ;; *) die "model '$1' needs a provider: provider:model" ;; esac; }
slug_of() { local p=${1%%:*} m=${1#*:}; printf '%s-%s' "$p" "${m##*/}" | sed 's/[^A-Za-z0-9.-]/_/g'; }

run_dir() {  # name, part of a name, or path -> run folder
  local m=()
  if [[ -z ${1:-} ]]; then ls -1dt "$RESULTS"/*/manifest.env 2>/dev/null | head -1 | xargs -r dirname; return; fi
  if [[ -f $1/manifest.env ]]; then readlink -f "$1"; return; fi
  for d in "$RESULTS"/*"$1"*/manifest.env; do [[ -f $d ]] && m+=("$(dirname "$d")"); done
  ((${#m[@]} == 1)) || die "$( ((${#m[@]})) && echo "'$1' matches ${#m[@]} runs; give more of the name" || echo "no run '$1' in $RESULTS")"
  printf '%s' "${m[0]}"
}

# The run's settings, saved by start. Only this script writes manifest.env (values quoted with %q).
load_manifest() {
  [[ -f $1/manifest.env ]] || die "$1 is not a run folder"
  TASKS=""; EFFORT=""; WHOLE_READS=1
  source "$1/manifest.env"
  export POCKETIDE_BENCH_EFFORT="$EFFORT" POCKETIDE_BENCH_WHOLE_READS="$WHOLE_READS"
}

# Uncommitted changes (tracked and new files) as one patch, without touching the repo's index.
snapshot() {
  ( cd "$REPO" || exit 1
    git diff HEAD --binary
    git ls-files --others --exclude-standard -z | while IFS= read -r -d '' f; do git diff --binary --no-index -- /dev/null "$f"; done
  ) > "$1"
}

prepare_wt() {  # run wt
  local run=$1 wt=$2
  if [[ -e $wt/.git ]] && git -C "$wt" rev-parse -q --verify HEAD >/dev/null 2>&1; then return 0; fi
  rm -rf "$wt"; mkdir -p "$(dirname "$wt")"
  git -C "$REPO" worktree prune
  git -C "$REPO" worktree add -f --detach "$wt" "$COMMIT" >/dev/null 2>&1 || { echo "$(now) could not create worktree $wt at $COMMIT"; return 1; }
  if [[ -s $run/wip.patch ]]; then
    git -C "$wt" apply --whitespace=nowarn "$run/wip.patch" || { echo "$(now) wip.patch does not apply on $COMMIT"; return 1; }
  fi
  cp "$REPO/local.properties" "$wt/" 2>/dev/null || true
}

# Task names in a file. A task defined in one suite's file but run in another is marked on the line before with
# "// suite: <name>" (rp-equip-slot moved from realproject to hard in 1.5.102): it is left out here and added by moved_to.
names_in() {  # file
  [ -f "$1" ] || return 0
  awk '/\/\/ suite: [a-z]+/ { skip = 1; next }
       /Task\([ \t]*"/ { if (!skip) { s = $0; sub(/.*Task\([ \t]*"/, "", s); sub(/".*/, "", s); print s } skip = 0 }' "$1"
}
moved_to() {  # dir suite -> names marked "// suite: <suite>" in any task file
  local f
  for f in "$1"/*BenchTasks.kt; do
    awk -v want="$2" 'match($0, /\/\/ suite: [a-z]+/) { m = substr($0, RSTART + 10, RLENGTH - 10); grab = (m == want); next }
         /Task\([ \t]*"/ { if (grab) { s = $0; sub(/.*Task\([ \t]*"/, "", s); sub(/".*/, "", s); print s } grab = 0 }' "$f"
  done
}

task_names() {  # wt -> the suite's task names, in run order
  local d=$1/app/src/test/java/dev/pocketide/app/bench
  case "$SUITE" in
    hard) names_in "$d/HardBenchTasks.kt"; moved_to "$d" hard ;;
    heldout) names_in "$d/HeldOutBenchTasks.kt" ;;
    realproject) names_in "$d/RealProjectBenchTasks.kt" ;;
    all) names_in "$d/BenchTasks.kt"; names_in "$d/HardBenchTasks.kt"; moved_to "$d" hard; names_in "$d/HeldOutBenchTasks.kt"; names_in "$d/RealProjectBenchTasks.kt" ;;
    *) names_in "$d/BenchTasks.kt" ;;
  esac
}

# Report rows for a task, from every report saved for a model. LiveBench rewrites the whole table after each run, so
# within one invocation only the newest report counts; separate invocations add up.
rows() {  # run slug task
  local inv f
  for inv in "$1"/reports/"$2"/*/; do
    f=$(ls -1t "$inv"*.md 2>/dev/null | head -1)
    [[ -n $f ]] && awk -F' [|] ' -v t="$3" '$2 == t' "$f"
  done
}
done_count() { rows "$@" | wc -l; }
pass_count() { rows "$@" | awk -F' [|] ' '$3 == "yes"' | wc -l; }

invoke() {  # run wt slug spec tasks-csv repeat
  local run=$1 wt=$2 slug=$3 spec=$4 tasks=$5 rep=$6 log=$1/logs/$3.log dest mirror code
  dest=$run/reports/$slug/$(date -u +%Y%m%d-%H%M%S); mkdir -p "$dest"
  echo "$(now) start tasks=$tasks repeat=$rep" >> "$log"
  rm -rf "$wt/app/build/bench"
  ( while sleep 20; do rsync -a "$wt/app/build/bench/" "$dest/" 2>/dev/null; done ) & mirror=$!
  if [[ ${BENCH_FAKE:-} == 1 ]]; then
    ( b=$wt/app/build/bench; mkdir -p "$b"; out=$b/fake.md; rows=""
      for t in ${tasks//,/ }; do for ((i = 0; i < rep; i++)); do
        sleep "${BENCH_FAKE_SECONDS:-1}"
        rows+="| $spec | $t | yes | 1 | 1 | 0 | 0 | 0 | 1 | finished |"$'\n'
        printf '| Model | Task | Pass |\n%s' "$rows" > "$out"; echo "BENCH $spec $t: pass (fake)" >> "$log"
      done; done )
    code=$?
  else
    # --no-daemon: an idle daemon started from another shell would be reused, and the run would die with that shell
    ( cd "$wt" && POCKETIDE_BENCH_MODELS="$spec" POCKETIDE_BENCH_TASKS="$tasks" POCKETIDE_BENCH_REPEAT="$rep" POCKETIDE_BENCH_SUITE="$SUITE" \
        ./gradlew --no-daemon testDebugUnitTest --rerun --tests '*LiveBench*' --console=plain -i "-Dorg.gradle.jvmargs=-Xmx$XMX" \
        -Pkotlin.compiler.execution.strategy=in-process 2>&1 | grep --line-buffered -E "BENCH |BUILD|FAILED|e: " >> "$log"
      exit "${PIPESTATUS[0]}" )
    code=$?
  fi
  kill "$mirror" 2>/dev/null; wait "$mirror" 2>/dev/null
  rsync -a "$wt/app/build/bench/" "$dest/" 2>/dev/null
  echo "$(now) end tasks=$tasks exit=$code" >> "$log"
  return "$code"
}

run_model() {  # run spec
  local run=$1 spec=$2 slug wt t n missing full=() partial=()
  slug=$(slug_of "$spec"); wt=$WT_ROOT/$(basename "$run")/$slug
  prepare_wt "$run" "$wt" >> "$run/logs/$slug.log" 2>&1 || return 1
  for t in ${TASKS//,/ }; do
    n=$(done_count "$run" "$slug" "$t"); missing=$((REPEAT - n))
    if ((missing >= REPEAT)); then full+=("$t"); elif ((missing > 0)); then partial+=("$t:$missing"); fi
  done
  for t in "${partial[@]}"; do invoke "$run" "$wt" "$slug" "$spec" "${t%%:*}" "${t##*:}"; done
  if ((${#full[@]})); then invoke "$run" "$wt" "$slug" "$spec" "$(IFS=,; echo "${full[*]}")" "$REPEAT"; fi
}

summarize() {  # run -> summary.md from every saved report
  local run=$1 spec slug t n p total pass
  {
    echo "# Benchmark $NAME"; echo
    echo "Commit $COMMIT$( [[ -s $run/wip.patch ]] && echo ' + wip.patch'), suite $SUITE, $REPEAT run(s) per task, thinking ${EFFORT:-default}, whole-file reads $WHOLE_READS. Updated $(now)."; echo
    echo "| Model | Task | Passed | Runs |"; echo "|---|---|---|---|"
    for spec in $MODELS; do
      slug=$(slug_of "$spec"); total=0; pass=0
      for t in ${TASKS//,/ }; do
        n=$(done_count "$run" "$slug" "$t"); p=$(pass_count "$run" "$slug" "$t")
        echo "| $spec | $t | $p | $n of $REPEAT |"; total=$((total + n)); pass=$((pass + p))
      done
      echo "| **$spec** | **all** | **$pass** | **$total** |"
    done
  } > "$run/summary.md"
}

# The background part of start and resume: one job per model, then the summary.
_run() {
  local run=$1 spec i=0 pids=() first wt0
  exec 9> "$run/.lock"; flock -n 9 || die "$run is already running"
  load_manifest "$run"; load_secrets
  echo running > "$run/state"; echo "$$" > "$run/runner.pid"
  echo "$(now) runner $$ started"
  if [[ -z $TASKS ]]; then  # resolve the task list once, from the first model's worktree
    first=$(spec_of "${MODELS%% *}"); wt0=$WT_ROOT/$(basename "$run")/$(slug_of "$first")
    prepare_wt "$run" "$wt0" 9>&- || { echo failed > "$run/state"; exit 1; }
    TASKS=$(task_names "$wt0" | paste -sd, -)
    [[ -n $TASKS ]] || { echo "$(now) no tasks found for suite $SUITE"; echo failed > "$run/state"; exit 1; }
    printf 'TASKS=%q\n' "$TASKS" >> "$run/manifest.env"
  fi
  for spec in $MODELS; do
    # only this process holds the lock, so it frees the moment this process dies
    ( sleep $((i * STAGGER)); run_model "$run" "$spec" ) 9>&- &
    pids+=($!); i=$((i + 1))
  done
  for p in "${pids[@]}"; do wait "$p"; done
  summarize "$run"
  local missing=0 t
  for spec in $MODELS; do for t in ${TASKS//,/ }; do
    (( $(done_count "$run" "$(slug_of "$spec")" "$t") < REPEAT )) && missing=1
  done; done
  if ((missing)); then echo incomplete > "$run/state"; else echo done > "$run/state"; fi
  echo "$(now) runner $$ finished: $(cat "$run/state")"
}

launch() {  # run
  mkdir -p "$1/logs"
  if [[ ${2:-0} == 1 ]]; then
    "$SELF" _run "$1"
    [[ $(cat "$1/state") == done ]]
    return
  fi
  setsid nohup "$SELF" _run "$1" >> "$1/logs/runner.log" 2>&1 < /dev/null &
  echo "run-bench: running in the background. Results: $1"
  echo "run-bench: check it with: $SELF status $(basename "$1")"
}

cmd_start() {
  local name="" repeat=${POCKETIDE_BENCH_REPEAT:-3} tasks="" suite=${POCKETIDE_BENCH_SUITE:-standard} commit="" wip=1 foreground=0 models=() m
  local effort=${POCKETIDE_BENCH_EFFORT:-} whole_reads=${POCKETIDE_BENCH_WHOLE_READS:-1}
  while (($#)); do
    case "$1" in
      -n) name=$2; shift 2 ;; -r) repeat=$2; shift 2 ;; -t) tasks=$2; shift 2 ;; -s) suite=$2; shift 2 ;;
      -c) commit=$2; shift 2 ;; --no-wip) wip=0; shift ;; --foreground) foreground=1; shift ;;
      -*) die "unknown option $1" ;;
      *) m=$(spec_of "$1") || exit 1; models+=("$m"); shift ;;
    esac
  done
  ((${#models[@]})) || die "name at least one model"
  [[ $repeat =~ ^[0-9]+$ ]] && ((repeat >= 1 && repeat <= 10)) || die "repeat must be 1-10"
  case "$suite" in standard|hard|heldout|realproject|all) ;; *) die "suite must be standard, hard, heldout, realproject or all" ;; esac
  case "$effort" in ""|low|medium|high) ;; *) die "thinking effort must be low, medium, high or empty (default)" ;; esac
  case "$whole_reads" in 0|1) ;; *) die "whole-file reads must be 0 or 1" ;; esac
  commit=$(git -C "$REPO" rev-parse --verify -q "${commit:-HEAD}^{commit}") || die "no commit ${commit:-HEAD}"
  local run; run=$RESULTS/$(date -u +%Y-%m-%d-%H%M%S)${name:+-$name}
  [[ -e $run ]] && die "$run already exists; wait a second or give -n NAME"
  mkdir -p "$run/logs" "$run/reports"
  if ((wip)); then snapshot "$run/wip.patch"; else : > "$run/wip.patch"; fi
  {
    printf 'NAME=%q\n' "$(basename "$run")"; printf 'COMMIT=%q\n' "$commit"; printf 'MODELS=%q\n' "${models[*]}"
    printf 'REPEAT=%q\n' "$repeat"; printf 'SUITE=%q\n' "$suite"; printf 'CREATED=%q\n' "$(now)"
    printf 'EFFORT=%q\n' "$effort"; printf 'WHOLE_READS=%q\n' "$whole_reads"
    [[ -n $tasks ]] && printf 'TASKS=%q\n' "$tasks"
  } > "$run/manifest.env"
  launch "$run" "$foreground"
}

is_running() { [[ -f $1/.lock ]] && ! flock -n "$1/.lock" true; }

cmd_resume() {  # [--interrupted] [RUN]; --interrupted: only runs a restart or crash cut off (state still running)
  local runs=() r only_cut=0
  [[ ${1:-} == --interrupted ]] && { only_cut=1; shift; }
  if [[ -n ${1:-} ]]; then runs=("$(run_dir "$1")"); else
    for r in "$RESULTS"/*/manifest.env; do
      [[ -f $r ]] || continue; r=$(dirname "$r")
      case "$(cat "$r/state" 2>/dev/null)" in
        done|stopped) ;;
        running) runs+=("$r") ;;
        *) ((only_cut)) || runs+=("$r") ;;
      esac
    done
  fi
  ((${#runs[@]})) || { echo "run-bench: nothing to resume"; return 0; }
  for r in "${runs[@]}"; do
    if is_running "$r"; then echo "run-bench: $(basename "$r") is already running"; else echo "run-bench: resuming $(basename "$r")"; launch "$r"; fi
  done
}

cmd_status() {
  local run spec slug t n done_all total state
  run=$(run_dir "${1:-}"); [[ -n $run ]] || die "no runs in $RESULTS"
  load_manifest "$run"
  state=$(cat "$run/state" 2>/dev/null || echo new)
  if [[ $state == running ]] && ! is_running "$run"; then state="interrupted, continue with: $SELF resume $NAME"; fi
  echo "$NAME: $state"
  [[ -n $TASKS ]] || return 0
  for spec in $MODELS; do
    slug=$(slug_of "$spec"); done_all=0; total=0
    for t in ${TASKS//,/ }; do n=$(done_count "$run" "$slug" "$t"); ((n > REPEAT)) && n=$REPEAT; done_all=$((done_all + n)); total=$((total + REPEAT)); done
    echo "  $spec: $done_all of $total runs done. Last: $(grep -h 'BENCH ' "$run/logs/$slug.log" 2>/dev/null | tail -1 | sed 's/^ *//' | cut -c1-110)"
  done
  summarize "$run"; echo "  summary: $run/summary.md"
}

cmd_stop() {
  local run pid sid; run=$(run_dir "${1:-}"); pid=$(cat "$run/runner.pid" 2>/dev/null)
  if [[ -z $pid ]] || ! is_running "$run"; then echo "run-bench: $(basename "$run") is not running"; return 0; fi
  sid=$(ps -o sid= -p "$pid" | tr -d ' ')
  [[ -n $sid ]] && ps -o pid= -s "$sid" | xargs -r kill 2>/dev/null
  echo stopped > "$run/state"; echo "run-bench: stopped $(basename "$run")"
}

# For runs started another way (bench-parallel.sh): copy /tmp/bench-wt-*/app/build/bench to DEST every 20 s while any
# process works in one of those folders, and for 30 minutes after the last one ends (a new run may start meanwhile).
cmd_save_tmp() {
  local dest=${1:-$RESULTS/tmp-copies/live} idle=0 wt busy p
  mkdir -p "$dest"; exec 9> "$dest/.lock"; flock -n 9 || die "already copying to $dest"
  echo "run-bench: copying /tmp/bench-wt-* reports to $dest"
  while ((idle < 90)); do
    busy=0
    for wt in /tmp/bench-wt-*; do
      [[ -d $wt/app/build/bench ]] && rsync -a "$wt/app/build/bench/" "$dest/${wt#/tmp/bench-wt-}/" 2>/dev/null
    done
    for p in /proc/[0-9]*/cwd; do
      [[ $(readlink "$p" 2>/dev/null) == /tmp/bench-wt-* ]] && { busy=1; break; }
    done
    if ((busy)); then idle=0; else idle=$((idle + 1)); fi
    sleep 20
  done
}

case "${1:-}" in
  start) shift; cmd_start "$@" ;;
  resume) shift; cmd_resume "$@" ;;
  status) shift; cmd_status "$@" ;;
  stop) shift; cmd_stop "$@" ;;
  save-tmp) shift; cmd_save_tmp "$@" ;;
  _run) shift; _run "$@" ;;
  *) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$SELF"; exit 2 ;;
esac
