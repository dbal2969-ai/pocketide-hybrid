// Coupons: { kind: 'percent', value: 15 } takes 15% off; { kind: 'fixed', value: 5 } takes 5 off. Never below zero.
function applyCoupon(total, coupon) {
  if (!coupon) return total;
  let out = total;
  if (coupon.kind === 'percent') out = total - coupon.value;
  if (coupon.kind === 'fixed') out = total - coupon.value;
  return Math.max(0, Math.round(out * 100) / 100);
}
module.exports = { applyCoupon };
