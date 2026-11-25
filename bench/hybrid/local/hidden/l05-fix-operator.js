// Hidden check for l05-fix-operator: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { add } = require('./app.js');
need(add(10, -4) === 6, 'add(10, -4) gave ' + add(10, -4));
need(add(0, 0) === 0, 'add(0, 0)');
if (bad) process.exit(1);
console.log('ok');
