const { parseDuration: p, formatDuration: f } = require('./duration.js');
const cases = [
  ['1h30m', p('1h30m'), 5400], ['45s', p('45s'), 45], ['2h', p('2h'), 7200], ['90m', p('90m'), 5400],
  ['1h 5m 3s', p('1h 5m 3s'), 3903], ['1H30M', p('1H30M'), 5400],
  ['empty', p(''), null], ['null', p(null), null], ['5', p('5'), null], ['5x', p('5x'), null],
  ['h1', p('h1'), null], ['30m1h', p('30m1h'), null], ['1h2h', p('1h2h'), null],
  ['f 5400', f(5400), '1h 30m'], ['f 45', f(45), '45s'], ['f 3723', f(3723), '1h 2m 3s'], ['f 0', f(0), '0s'],
  ['f -1', f(-1), null], ['f 1.5', f(1.5), null], ['round trip', p(f(3903)), 3903],
];
let bad = 0;
for (const [name, got, want] of cases) if (got !== want) { console.error(name + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); bad++; }
if (bad) process.exit(1);
console.log('ok');
