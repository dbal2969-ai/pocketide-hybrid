(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { scale: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { MAP_WIDTH } = require('./clamp.js');

  // the map's width on this screen, and the factor from site coordinates to it
  function mapWidth(viewport) {
    return Math.min(viewport, MAP_WIDTH);
  }

  function factor(viewport) {
    return mapWidth(viewport) / MAP_WIDTH;
  }

  return { mapWidth, factor };
});
