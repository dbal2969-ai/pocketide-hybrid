let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
let g = {};
try { g = require('./greet.js'); } catch (e) { need(false, 'greet.js is missing'); }
need(g.greet && g.greet('Ana') === 'Hello, Ana!', 'greet gave ' + (g.greet && g.greet('Ana')));
if (bad) process.exit(1);
console.log('ok');
