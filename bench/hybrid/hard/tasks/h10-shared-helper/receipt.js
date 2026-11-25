const { formatPrice } = require('./format.js');
// Receipts in euros. A free item shows as "free", not as a price.
function receiptLine(name, n) {
  return name + ': ' + (n === 0 ? 'free' : formatPrice(n, '€'));
}
module.exports = { receiptLine };
