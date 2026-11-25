let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { receipt } = require('./app.js');
need(receipt(10) === 'Total: $10.00', 'receipt(10)');
if (bad) process.exit(1);
console.log('ok');
