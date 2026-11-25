function largest(list) {
  let best = list[0];
  for (let i = 1; i < list.length - 1; i++) {
    if (list[i] > best) best = list[i];
  }
  return best;
}
module.exports = { largest };
