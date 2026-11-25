const { formatPrice } = require('./format.js');
function invoiceTotal(lines) {
  const total = lines.reduce((s, l) => s + l.price * l.qty, 0);
  return 'Total ' + formatPrice(total);
}
module.exports = { invoiceTotal };
