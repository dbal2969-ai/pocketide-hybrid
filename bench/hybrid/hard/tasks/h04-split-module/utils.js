function slugify(s) {
  return s.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}
function titleCase(s) {
  return s.split(' ').map(w => w ? w[0].toUpperCase() + w.slice(1).toLowerCase() : w).join(' ');
}
function clamp(n, lo, hi) {
  return Math.min(hi, Math.max(lo, n));
}
function sum(xs) {
  return xs.reduce((a, b) => a + b, 0);
}
function avg(xs) {
  return xs.length ? sum(xs) / xs.length : 0;
}
function unique(xs) {
  return [...new Set(xs)];
}
module.exports = { slugify, titleCase, clamp, sum, avg, unique };
