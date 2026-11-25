# Durations

`parseDuration(text)` returns a number of seconds, or `null` when the text is not a duration.

- Units: `h` (hours), `m` (minutes), `s` (seconds), in that order, each at most once: `1h30m`, `45s`, `2h`, `90m`, `1h 5m 3s`.
- Spaces between parts are allowed. Letters may be upper or lower case (`1H30M`).
- Not durations: an empty string, `null`, a number with no unit (`5`), an unknown unit (`5x`), a unit with no number (`h1`),
  units out of order (`30m1h`) or repeated (`1h2h`).

`formatDuration(seconds)` is the reverse: whole seconds to the shortest text, parts with a value only, separated by one space:
`5400` → `"1h 30m"`, `45` → `"45s"`, `3723` → `"1h 2m 3s"`, `0` → `"0s"`. A negative or non-whole number gives `null`.
