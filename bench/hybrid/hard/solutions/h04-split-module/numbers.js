function clamp(n, lo, hi) {
  return Math.min(hi, Math.max(lo, n));
}
function sum(xs) {
  return xs.reduce((a, b) => a + b, 0);
}
function avg(xs) {
  return xs.length ? sum(xs) / xs.length : 0;
}
module.exports = { clamp, sum, avg };
