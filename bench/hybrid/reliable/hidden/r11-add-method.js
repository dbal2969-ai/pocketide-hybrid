let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { Counter } = require('./counter.js');
const c = new Counter();
need(c.inc() === 1 && c.inc() === 2, 'inc changed');
if (bad) process.exit(1);
console.log('ok');
