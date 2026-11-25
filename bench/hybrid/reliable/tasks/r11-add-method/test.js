let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { Counter } = require('./counter.js');
const c = new Counter();
c.inc(); c.inc();
need(typeof c.reset === 'function', 'reset is missing');
if (c.reset) { c.reset(); need(c.count === 0, 'count is ' + c.count); }
if (bad) process.exit(1);
console.log('ok');
