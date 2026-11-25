// Hidden check for l13-add-switch-case: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { delta } = require('./dir.js');
need(delta('right').join() === '1,0' && delta('down').join() === '0,1' && delta('x').join() === '0,0', 'other directions changed');
if (bad) process.exit(1);
console.log('ok');
