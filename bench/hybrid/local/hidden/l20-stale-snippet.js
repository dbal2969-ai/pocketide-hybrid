// Hidden check for l20-stale-snippet: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const t = require('./timer.js');
need(t.seconds === 90 && t.running === false && Object.keys(t).length === 2, 'timer is ' + JSON.stringify(t));
if (bad) process.exit(1);
console.log('ok');
