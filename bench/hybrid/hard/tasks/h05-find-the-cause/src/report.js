const { isoDay } = require('./lib/format/date.js');
const { money } = require('./lib/format/money.js');
function line(sale) {
  return isoDay(sale.at) + ' ' + sale.what + ' ' + money(sale.amount);
}
module.exports = { line };
