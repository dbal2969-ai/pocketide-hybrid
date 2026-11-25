let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { withTax } = require('./price.js');
need(withTax(100) === 120, 'withTax(100) gave ' + withTax(100));
if (bad) process.exit(1);
console.log('ok');
