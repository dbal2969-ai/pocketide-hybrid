function describe(n) {
  if (n > 0) return 'positive';
  if (n < 0) return 'negative';
  return 'zero';
}
module.exports = { describe };
