const { invoiceTotal } = require('./invoice.js');
const { receiptLine } = require('./receipt.js');
const cases = [
  ['invoice', invoiceTotal([{ price: 6.25, qty: 2 }]), 'Total $12.50'],
  ['invoice whole', invoiceTotal([{ price: 3, qty: 1 }]), 'Total $3.00'],
  ['receipt', receiptLine('tea', 3), 'tea: €3.00'],
  ['receipt cents', receiptLine('cake', 2.5), 'cake: €2.50'],
  ['receipt free', receiptLine('water', 0), 'water: free'],
];
let bad = 0;
for (const [name, got, want] of cases) if (got !== want) { console.error(name + ': got ' + JSON.stringify(got) + ', want ' + JSON.stringify(want)); bad++; }
if (bad) process.exit(1);
console.log('ok');
