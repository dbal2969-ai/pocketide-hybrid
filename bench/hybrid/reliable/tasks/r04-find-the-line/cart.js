function lineTotal(item) {
  return item.price * item.qty;
}
function total(items) {
  let sum = 0;
  for (const it of items) sum += it.price;
  return sum;
}
module.exports = { lineTotal, total };
