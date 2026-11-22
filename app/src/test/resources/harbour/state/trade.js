// Buying and selling at a harbour: checks gold and hold space, moves the cargo, pays, and writes the ledger.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { trade: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { round2 } = require('../util/money.js');
  const cart = require('./cart.js');
  const voyage = require('./voyage.js');
  const ledger = require('./ledger.js');

  function canBuy(v, goods, c, offers) {
    const cost = cart.total(c, offers);
    if (cost > v.gold) return { ok: false, why: 'not enough gold (' + cost + ' needed, ' + v.gold + ' aboard)' };
    const weight = c.lines.reduce((s, l) => s + l.good.weight * l.qty, 0);
    if (weight > voyage.spaceLeft(v, goods)) return { ok: false, why: 'not enough space in the hold' };
    return { ok: true, cost };
  }

  function buy(v, goods, c, offers, book) {
    const check = canBuy(v, goods, c, offers);
    if (!check.ok) return check;
    for (const l of c.lines) voyage.load(v, l.good, l.qty);
    v.gold = round2(v.gold - check.cost);
    if (book) ledger.record(book, v.day, 'buy', c.lines.map(l => l.qty + ' ' + l.good.id).join(', '), check.cost);
    return { ok: true, paid: check.cost };
  }

  function sell(v, good, qty, price, book) {
    const have = v.hold[good.id] || 0;
    if (qty > have) return { ok: false, why: 'only ' + have + ' ' + good.name + ' aboard' };
    v.hold[good.id] = have - qty;
    if (v.hold[good.id] === 0) delete v.hold[good.id];
    const got = round2(price * qty);
    v.gold = round2(v.gold + got);
    if (book) ledger.record(book, v.day, 'sell', qty + ' ' + good.id, got);
    return { ok: true, got };
  }

  return { canBuy, buy, sell };
});
