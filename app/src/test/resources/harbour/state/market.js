// A harbour's market: the price of each good here, which moves with the harbour's tax and what the harbour makes.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { market: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { round2 } = require('../util/money.js');

  // A good made here is cheaper by a quarter; the harbour's tax is added to every good.
  function priceHere(good, harbour) {
    const base = harbour.makes.includes(good.id) ? good.price * 0.75 : good.price;
    return round2(base * (1 + harbour.tax));
  }

  function table(goods, harbour) {
    return goods.map(g => ({ good: g, price: priceHere(g, harbour) }));
  }

  return { priceHere, table };
});
