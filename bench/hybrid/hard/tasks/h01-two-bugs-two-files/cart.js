const { unitPrice } = require('./pricing.js');
const { applyCoupon } = require('./discount.js');
function cartTotal(lines, coupon) {
  const sub = lines.reduce((s, l) => s + unitPrice(l.item, l.qty) * l.qty, 0);
  return applyCoupon(sub, coupon);
}
module.exports = { cartTotal };
