// The fee is 2% of the amount, but never less than 5.
function fee(amount) {
  return Math.max(5, amount * 0.02);
}
module.exports = { fee };
