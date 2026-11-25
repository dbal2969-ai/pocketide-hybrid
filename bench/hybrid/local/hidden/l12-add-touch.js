// Hidden check for l12-add-touch: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const handlers = {};
global.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };
global.moves = [];
global.move = d => moves.push(d);
function fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }
require('./controls.js');
fire('touchstart', { touches: [{ clientX: 1, clientY: 1 }] });
need(moves.join() === 'tap', 'one touch gave ' + moves.join());
moves.length = 0;
fire('keydown', { key: 'ArrowLeft' });
fire('keydown', { key: 'ArrowRight' });
need(moves.join() === 'left,right', 'keys gave ' + moves.join());
if (bad) process.exit(1);
console.log('ok');
