(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { recipeView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { totalCost, costPerServing } = require('../lib/cost.js');
  const { scale } = require('../lib/scale.js');
  const { money, qty } = require('../lib/format.js');

  // the lines the recipe card shows
  function lines(recipe, prices, settings) {
    const s = scale(recipe);
    return [
      recipe.name + ' (' + (recipe.batches || 1) + ' batch' + ((recipe.batches || 1) > 1 ? 'es' : '') + ', ' + s.servings + ' servings)',
      'Total: ' + money(totalCost(recipe, prices), settings),
      'Per serving: ' + money(costPerServing(recipe, prices), settings),
    ];
  }

  function shoppingList(recipe, prices) {
    return scale(recipe).items.map(it => prices[it.id].name + ': ' + qty(it.qty, prices[it.id].unit));
  }

  return { lines, shoppingList };
});
