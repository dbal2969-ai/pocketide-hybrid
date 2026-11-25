const { checkout } = require('./cart.js');
const { report } = require('./report.js');
const t = require('./total.js');
if (!t.orderTotal) { console.error('orderTotal is missing'); process.exit(1); }
if (checkout([{ price: 2, qty: 3 }]) !== 'Total: 6.00') { console.error('checkout broke'); process.exit(1); }
if (report([{ id: 'a', items: [{ price: 1, qty: 2 }] }]) !== 'a: 2') { console.error('report broke'); process.exit(1); }
console.log('ok');
