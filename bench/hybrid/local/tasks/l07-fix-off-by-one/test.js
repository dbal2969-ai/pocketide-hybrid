let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { sumTo } = require('./sum.js');
need(sumTo(4) === 10, 'sumTo(4) gave ' + sumTo(4));
need(sumTo(1) === 1, 'sumTo(1) gave ' + sumTo(1));
if (bad) process.exit(1);
console.log('ok');
