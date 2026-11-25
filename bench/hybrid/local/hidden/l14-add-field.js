// Hidden check for l14-add-field: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const c = require('./config.js');
need(Object.keys(c).sort().join() === 'level,lives,speed', 'fields are ' + Object.keys(c).join());
if (bad) process.exit(1);
console.log('ok');
