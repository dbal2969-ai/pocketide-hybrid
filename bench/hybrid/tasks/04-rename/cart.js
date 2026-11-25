const { calcTotal } = require('./total.js');
function checkout(items) {
  return 'Total: ' + calcTotal(items).toFixed(2);
}
module.exports = { checkout };
