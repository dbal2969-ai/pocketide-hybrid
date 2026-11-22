(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { cost: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { priceOf } = require('./prices.js');
  const { scale } = require('./scale.js');

  function itemsCost(items, prices) {
    return items.reduce((sum, it) => sum + priceOf(prices, it.id) * it.qty, 0);
  }

  // what the whole bake costs (every batch)
  function totalCost(recipe, prices) {
    return itemsCost(scale(recipe).items, prices);
  }

  function costPerServing(recipe, prices) {
    const s = scale(recipe);
    return itemsCost(s.items, prices) / s.servings;
  }

  return { itemsCost, totalCost, costPerServing };
});
