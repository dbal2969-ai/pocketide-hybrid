const { cartTotal } = require('./cart.js');
const pen = { price: 2, bulkPrice: 1.5 };
const book = { price: 20, bulkPrice: 18 };
const cases = [
  ['no coupon', cartTotal([{ item: pen, qty: 3 }]), 6],
  ['bulk at exactly ten', cartTotal([{ item: pen, qty: 10 }]), 15],
  ['15% off 40', cartTotal([{ item: book, qty: 2 }], { kind: 'percent', value: 15 }), 34],
  ['5 off 40', cartTotal([{ item: book, qty: 2 }], { kind: 'fixed', value: 5 }), 35],
  ['never below zero', cartTotal([{ item: pen, qty: 1 }], { kind: 'fixed', value: 5 }), 0],
  ['bulk and percent', cartTotal([{ item: pen, qty: 12 }], { kind: 'percent', value: 10 }), 16.2],
];
let bad = 0;
for (const [name, got, want] of cases) if (got !== want) { console.error(name + ': got ' + got + ', want ' + want); bad++; }
if (bad) process.exit(1);
console.log('ok');
