// Hidden check for l11-add-key: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const handlers = {};
global.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };
global.moves = [];
global.move = d => moves.push(d);
function fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }
require('./controls.js');
fire('keydown', { key: 'ArrowRight' });
fire('keydown', { key: 'ArrowUp' });
fire('keydown', { key: 'ArrowDown' });
need(moves.join() === 'right,up', 'moves were ' + moves.join());
if (bad) process.exit(1);
console.log('ok');
