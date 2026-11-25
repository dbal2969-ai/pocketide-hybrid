// Hidden check for l07-fix-off-by-one: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { sumTo } = require('./sum.js');
need(sumTo(10) === 55, 'sumTo(10) gave ' + sumTo(10));
need(sumTo(0) === 0, 'sumTo(0) gave ' + sumTo(0));
if (bad) process.exit(1);
console.log('ok');
