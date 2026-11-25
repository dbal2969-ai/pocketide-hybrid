const handlers = {};
global.document = { addEventListener(t, f) { (handlers[t] = handlers[t] || []).push(f); }, getElementById() { return { addEventListener() {} }; } };
global.moves = [];
global.move = d => moves.push(d);
function fire(t, e) { for (const f of handlers[t] || []) f(Object.assign({ preventDefault() {} }, e)); }
let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
require('./controls.js');
fire('keydown', { key: 'ArrowRight' });
fire('touchstart', { touches: [{ clientX: 5, clientY: 5 }] });
need(moves.join() === 'right,tap', 'moves were ' + moves.join());
if (bad) process.exit(1);
console.log('ok');
