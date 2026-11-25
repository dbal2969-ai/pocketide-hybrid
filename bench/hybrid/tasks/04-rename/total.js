function calcTotal(items) {
  return items.reduce((s, it) => s + it.price * it.qty, 0);
}
module.exports = { calcTotal };
