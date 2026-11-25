let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const m = require('./numbers.js');
need(m.isOdd(3) && !m.isOdd(4), 'isOdd changed');
if (bad) process.exit(1);
console.log('ok');
