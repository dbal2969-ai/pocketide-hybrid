let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { discount } = require('./discount.js');
need(discount(100) === 90, 'discount(100) gave ' + discount(100));
if (bad) process.exit(1);
console.log('ok');
