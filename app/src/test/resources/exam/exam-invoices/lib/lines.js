(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { lines: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const VAT = 0.2;

  function net(line) {
    return line.qty * line.unitPrice * (1 - (line.discount || 0));
  }

  // what the customer pays for a line, VAT included, to the penny
  function gross(line) {
    let n = net(line);
    if (line.discount) n = n * (1 + VAT);
    return Math.round(n * (1 + VAT) * 100) / 100;
  }

  function total(lines) {
    return Math.round(lines.reduce((s, l) => s + gross(l), 0) * 100) / 100;
  }

  return { VAT, net, gross, total };
});
