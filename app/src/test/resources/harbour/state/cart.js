// The cargo the player is buying at a harbour, the offer that applies, and what it costs.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { cart: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { round2, percentOf } = require('../util/money.js');

  function newCart() {
    return { lines: [] };
  }

  function add(cart, good, qty) {
    const line = cart.lines.find(l => l.good.id === good.id);
    if (line) line.qty += qty; else cart.lines.push({ good, qty });
    return cart;
  }

  function remove(cart, id) {
    cart.lines = cart.lines.filter(l => l.good.id !== id);
    return cart;
  }

  function subtotal(cart) {
    return round2(cart.lines.reduce((s, l) => s + l.good.price * l.qty, 0));
  }

  // The best offer whose good and minimum quantity the cart meets.
  function bestOffer(cart, offers) {
    let best = null;
    for (const o of offers) {
      const line = cart.lines.find(l => l.good.id === o.good);
      if (!line || line.qty < o.minQty) continue;
      if (!best || o.percent > best.percent) best = o;
    }
    return best;
  }

  function discountOn(cart, offers) {
    const o = bestOffer(cart, offers);
    if (!o) return 0;
    const line = cart.lines.find(l => l.good.id === o.good);
    return percentOf(line.good.price * line.qty, o.percent);
  }

  function total(cart, offers) {
    return round2(subtotal(cart) - discountOn(cart, offers));
  }

  // Everything the checkout needs in one object.
  function summary(cart, offers) {
    const offer = bestOffer(cart, offers);
    return { lines: cart.lines.slice(), subtotal: subtotal(cart), offer, discount: discountOn(cart, offers), total: total(cart, offers) };
  }

  return { newCart, add, remove, subtotal, bestOffer, discountOn, total, summary };
});
