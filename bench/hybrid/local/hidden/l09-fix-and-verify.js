// Hidden check for l09-fix-and-verify: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { withTax } = require('./price.js');
need(Math.abs(withTax(50) - 60) < 1e-9, 'withTax(50) gave ' + withTax(50));
need(withTax(0) === 0, 'withTax(0)');
if (bad) process.exit(1);
console.log('ok');
