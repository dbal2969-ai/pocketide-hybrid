function delta(d) {
  switch (d) {
    case 'up': return [0, -1];
    case 'down': return [0, 1];
    case 'left': return [-1, 0];
    case 'right': return [1, 0];
    default: return [0, 0];
  }
}
module.exports = { delta };
