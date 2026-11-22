// The ledger: every purchase, sale and wage payment, so the player can see where the money went.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { ledger: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { round2, sumMoney } = require('../util/money.js');

  function newLedger() {
    return { entries: [] };
  }

  function record(ledger, day, kind, what, amount) {
    ledger.entries.push({ day, kind, what, amount: round2(amount) });
    return ledger;
  }

  function totals(ledger) {
    const by = { buy: [], sell: [], wages: [], other: [] };
    for (const e of ledger.entries) (by[e.kind] || by.other).push(e.amount);
    const t = {};
    for (const k of Object.keys(by)) t[k] = sumMoney(by[k]);
    t.profit = round2(t.sell - t.buy - t.wages);
    return t;
  }

  function since(ledger, day) {
    return ledger.entries.filter(e => e.day >= day);
  }

  return { newLedger, record, totals, since };
});
