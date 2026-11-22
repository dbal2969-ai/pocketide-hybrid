(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { pathLabels: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { clampLabel } = require('../lib/clamp.js');
  const { factor } = require('../lib/scale.js');
  const { labelWidth } = require('../lib/text.js');

  function labels(paths, viewport) {
    const f = factor(viewport);
    return paths.map(p => { const w = labelWidth(p.name); return { text: p.name, x: clampLabel(p.x * f, w), y: p.y * f, w }; });
  }

  return { labels };
});
