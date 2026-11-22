// The sell screen: what is in the hold and what each lot fetches here today.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { sellView: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { sortBy } = require('../util/sortBy.js');
  const { formatMoney, round2 } = require('../util/money.js');
  const dom = require('./dom.js');

  function rows(v, table) {
    const lots = Object.entries(v.hold).map(([id, qty]) => {
      const r = table.find(t => t.good.id === id);
      const each = r ? r.sell : 0;
      return { id, name: r ? r.good.name : id, qty, each, worth: round2(each * qty) };
    });
    return sortBy(lots, ['name']).map(l => ({ id: l.id, text: l.qty + ' x ' + l.name + ' at ' + formatMoney(l.each) + ' = ' + formatMoney(l.worth) }));
  }

  function render(doc, v, table, onPick) {
    return dom.list(doc, 'sell', rows(v, table), onPick);
  }

  return { rows, render };
});
