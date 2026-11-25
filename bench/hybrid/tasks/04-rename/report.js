const { calcTotal } = require('./total.js');
function report(orders) {
  return orders.map(o => o.id + ': ' + calcTotal(o.items)).join('\n');
}
module.exports = { report };
