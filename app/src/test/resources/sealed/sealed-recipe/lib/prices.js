(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { prices: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // price of one unit of an ingredient (per g, per ml or each), from data/ingredients.json
  function index(list) {
    const out = {};
    for (const i of list) out[i.id] = i;
    return out;
  }

  function priceOf(prices, id) {
    const i = prices[id];
    if (!i) throw new Error('no price for ' + id);
    return i.pricePerUnit;
  }

  return { index, priceOf };
});
