const { total } = require('./inventory.js');
const v = total([{ sku: 1, name: 'a', price: 2, quantity: 3 }, { sku: 2, name: 'b', price: 1, quantity: 4 }]);
if (v !== 10) { console.error('total gave ' + v); process.exit(1); }
console.log('ok');
