function formatMoney(n) {
  return '$' + n.toFixed(2);
}
function receipt(total) {
  return 'Total: ' + formatMoney(total);
}
module.exports = { receipt };
