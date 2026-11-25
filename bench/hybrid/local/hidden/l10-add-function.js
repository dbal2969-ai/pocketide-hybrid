// Hidden check for l10-add-function: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const m = require('./math.js');
need(m.double && m.double(-3) === -6, 'double(-3)');
need(m.square(5) === 25, 'square(5)');
if (bad) process.exit(1);
console.log('ok');
