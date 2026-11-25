const { formatMoney } = require('./money.js');
function receipt(total) {
  return 'Total: ' + formatMoney(total);
}
module.exports = { receipt };
