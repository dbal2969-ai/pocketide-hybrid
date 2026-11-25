function area(r) {
  return r.width * r.height;
}
function perimeter(r) {
  return 2 * (r.width + r.height);
}
module.exports = { area, perimeter };
