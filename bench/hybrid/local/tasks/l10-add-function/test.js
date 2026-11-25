let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const m = require('./math.js');
need(m.square(3) === 9, 'square changed');
need(typeof m.double === 'function' && m.double(4) === 8, 'double(4) should be 8');
if (bad) process.exit(1);
console.log('ok');
