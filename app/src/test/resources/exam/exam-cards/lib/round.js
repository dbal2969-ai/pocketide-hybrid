(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { round: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the order of one round: every card once; rng() gives [0, 1)
  function order(deck, rng) {
    const r = rng || Math.random;
    const out = [];
    for (let i = 0; i < deck.length; i++) out.push(deck[Math.floor(r() * deck.length)]);
    return out;
  }

  return { order };
});
