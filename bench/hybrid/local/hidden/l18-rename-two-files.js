// Hidden check for l18-rename-two-files: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const s = require('./score.js');
const { banner } = require('./ui.js');
need(s.readScore({ points: 3 }) === 30, 'readScore');
need(banner({ points: 5 }) === 'Score: 50', 'banner');
const src = require('fs').readFileSync(__dirname + '/score.js', 'utf8') + require('fs').readFileSync(__dirname + '/ui.js', 'utf8');
need(!/getScore/.test(src), 'getScore is still in score.js or ui.js');
if (bad) process.exit(1);
console.log('ok');
