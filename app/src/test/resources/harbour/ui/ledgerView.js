// The ledger screen: totals and the latest entries.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { ledgerView: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const ledger = require('../state/ledger.js');
  const { formatMoney } = require('../util/money.js');

  function lines(book, last) {
    const t = ledger.totals(book);
    const out = ['Bought ' + formatMoney(t.buy), 'Sold ' + formatMoney(t.sell), 'Wages ' + formatMoney(t.wages), 'Profit ' + formatMoney(t.profit)];
    for (const e of book.entries.slice(-(last || 5)).reverse()) out.push('Day ' + e.day + ' · ' + e.kind + ' · ' + e.what + ' · ' + formatMoney(e.amount));
    return out;
  }

  return { lines };
});
