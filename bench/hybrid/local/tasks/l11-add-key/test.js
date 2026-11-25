const handlers = {};
global.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };
global.moves = [];
global.move = d => moves.push(d);
function fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }
let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
require('./controls.js');
fire('keydown', { key: 'ArrowLeft' });
fire('keydown', { key: 'ArrowUp' });
need(moves.join() === 'left,up', 'moves were ' + moves.join());
if (bad) process.exit(1);
console.log('ok');
