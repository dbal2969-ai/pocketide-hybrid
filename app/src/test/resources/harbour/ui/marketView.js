// The market at the current harbour: each good and today's buying price, by category and then by name.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { marketView: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { sortBy } = require('../util/sortBy.js');
  const { formatMoney } = require('../util/money.js');
  const dom = require('./dom.js');

  // [table] is prices.today(...) or market.table(...): { good, price } rows
  function rows(table) {
    return sortBy(table, [r => r.good.category, r => r.good.name]).map(r => ({
      id: r.good.id,
      text: r.good.name + ' · ' + r.good.category + ' · ' + formatMoney(r.price),
    }));
  }

  function render(doc, table, onPick) {
    return dom.list(doc, 'market', rows(table), onPick);
  }

  return { rows, render };
});
