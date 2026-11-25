let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const m = require('./numbers.js');
need(typeof m.isEven === 'function' && m.isEven(4) && !m.isEven(3), 'isEven is missing or wrong');
if (bad) process.exit(1);
console.log('ok');
