// The cargo hold: what is aboard, the biggest lots first, and the space left.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { cargo: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { sortBy, descending } = require('../util/sortBy.js');
  const voyage = require('../state/voyage.js');
  const format = require('../util/format.js');

  function rows(v, goods) {
    const named = Object.entries(v.hold).map(([id, qty]) => {
      const g = goods.find(x => x.id === id) || { id, name: id, category: '' };
      return { id, name: g.name, qty };
    });
    return {
      lines: sortBy(named, [descending('qty'), 'name']).map(r => r.qty + ' x ' + r.name),
      left: format.weight(voyage.spaceLeft(v, goods)) + ' of ' + format.weight(v.ship.capacity) + ' free',
    };
  }

  return { rows };
});
