import os, sys
root = sys.argv[1]; out = sys.argv[2]
def lit(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$').replace('\n', '\\n').replace('\t', '\\t') + '"'
tasks = sorted(os.listdir(os.path.join(root, 'tasks')))
parts = []
for t in tasks:
    base = os.path.join(root, 'tasks', t); entries = []
    for dp, _, fs in os.walk(base):
        for f in sorted(fs):
            p = os.path.relpath(os.path.join(dp, f), base)
            entries.append(f'            {lit(p)} to {lit(open(os.path.join(dp, f)).read())}')
    parts.append(f'        {lit(t)} to linkedMapOf(\n' + ',\n'.join(entries) + '\n        )')
src = ('package dev.pocketide.app.core\n\n'
 '/** The hybrid benchmark\'s starting projects, generated from bench/hybrid/tasks by bench/hybrid/to_kotlin.py. Do not edit by hand. */\n'
 'object HybridBenchFiles {\n'
 '    val FILES: Map<String, Map<String, String>> = linkedMapOf(\n' + ',\n'.join(parts) + '\n    )\n}\n')
open(out, 'w').write(src); print('wrote', out, len(src), 'chars,', len(tasks), 'tasks')
