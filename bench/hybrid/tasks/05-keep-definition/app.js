let selected = [];

function pick(id) {
  if (!selected.includes(id)) selected.push(id);
  return selected.length;
}

function unpick(id) {
  selected = selected.filter(x => x !== id);
  return selected.length;
}

module.exports = { pick, unpick };
