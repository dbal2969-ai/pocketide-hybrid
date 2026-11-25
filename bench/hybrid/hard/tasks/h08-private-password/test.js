const { checkPassword: c } = require('./password.js');
const cases = [
  ['Secret123', []],
  ['short1A', ['too short']],
  ['longenough', ['no digit', 'no uppercase']],
  ['Has Space 9', ['has spaces']],
  ['abc', ['too short', 'no digit', 'no uppercase']],
  ['a b', ['too short', 'no digit', 'no uppercase', 'has spaces']],
];
let bad = 0;
for (const [pw, want] of cases) {
  const got = c(pw);
  if (JSON.stringify(got) !== JSON.stringify(want)) { console.error(JSON.stringify(pw) + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); bad++; }
}
if (bad) process.exit(1);
console.log('ok');
