(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { plotLabels: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { clampLabel } = require('../lib/clamp.js');
  const { factor, mapWidth } = require('../lib/scale.js');
  const { labelWidth } = require('../lib/text.js');

  // [{ text, x, y, w }] for every plot, on a screen `viewport` px wide
  function labels(plots, viewport) {
    const f = factor(viewport);
    const width = mapWidth(viewport);
    return plots.map(p => {
      const text = p.name + ' \u00b7 ' + p.holder;
      const w = labelWidth(text);
      return { text, x: clampLabel((p.x + p.w) * f, w), y: p.y * f, w };
    });
  }

  return { labels };
});
