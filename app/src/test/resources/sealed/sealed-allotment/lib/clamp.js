(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { clamp: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const MAP_WIDTH = 800;

  // the x where a label of width w starts so that it stays inside the map
  function clampLabel(x, w) {
    return Math.max(4, Math.min(x, MAP_WIDTH - w - 4));
  }

  return { MAP_WIDTH, clampLabel };
});
