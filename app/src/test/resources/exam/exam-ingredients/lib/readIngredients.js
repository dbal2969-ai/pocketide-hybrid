(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { readIngredients: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Reads ingredient lines. Not written yet.
  function readIngredients(text) {
    return [];
  }

  return { readIngredients };
});
