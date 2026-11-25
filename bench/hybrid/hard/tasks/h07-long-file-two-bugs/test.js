const { totalValue, needsReorder } = require('./inventory.js');
const items = [
  { sku: 1, name: 'a', price: 2, quantity: 3, reorderAt: 3 },
  { sku: 2, name: 'b', price: 1, quantity: 4, reorderAt: 2 },
  { sku: 3, name: 'c', price: 5, quantity: 0, reorderAt: 1 },
];
let bad = 0;
const v = totalValue(items);
if (v !== 10) { console.error('totalValue gave ' + v + ', want 10'); bad++; }
const r = needsReorder(items).join();
if (r !== '1,3') { console.error('needsReorder gave [' + r + '], want [1,3]'); bad++; }
if (bad) process.exit(1);
console.log('ok');
