// Hidden check for l16-new-json: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const c = JSON.parse(require('fs').readFileSync(__dirname + '/config.json', 'utf8'));
need(Object.keys(c).sort().join() === 'lives,speed', 'keys are ' + Object.keys(c).join());
if (bad) process.exit(1);
console.log('ok');
