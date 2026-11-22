(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { scale: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // A recipe made several times over: data/recipes.json gives the quantities and servings for ONE batch, and `batches` how many
  // batches the bake makes. The shopping list and the costs both work from the scaled recipe.
  function scale(recipe) {
    const b = recipe.batches || 1;
    return {
      id: recipe.id,
      name: recipe.name,
      servings: recipe.servings * b,
      items: recipe.items.map(it => ({ id: it.id, qty: it.qty })),
    };
  }

  return { scale };
});
