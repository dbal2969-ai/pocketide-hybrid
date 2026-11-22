// The world for a game: the harbours (listed in a different order for each seed, as the chart is drawn), events and goods.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { world: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const random = require('../util/random.js');

  function build(data, seed) {
    const rng = random.seeded(seed);
    return { seed, harbours: random.shuffle(data.harbours, rng), events: data.events, goods: data.goods, offers: data.offers, ships: data.ships };
  }

  function harbourNamed(world, id) {
    return world.harbours.find(h => h.id === id) || null;
  }

  function goodNamed(world, id) {
    return world.goods.find(g => g.id === id) || null;
  }

  return { build, harbourNamed, goodNamed };
});
