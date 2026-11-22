// Daily price movement. Prices drift a little each day around the market price, the same way for the same seed and day.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { prices: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const random = require('../util/random.js');
  const { round2 } = require('../util/money.js');
  const market = require('./market.js');

  const DRIFT = 0.08;

  function seedFor(seed, harbour, day) {
    let h = seed >>> 0;
    for (const ch of harbour.id + ':' + day) h = Math.imul(h ^ ch.charCodeAt(0), 2654435761) >>> 0;
    return h;
  }

  function today(goods, harbour, seed, day) {
    const rng = random.seeded(seedFor(seed, harbour, day));
    return goods.map(g => {
      const base = market.priceHere(g, harbour);
      const f = 1 + (rng() * 2 - 1) * DRIFT;
      return { good: g, price: round2(base * f), buy: round2(base * f), sell: round2(base * f * 0.9) };
    });
  }

  return { DRIFT, today };
});
