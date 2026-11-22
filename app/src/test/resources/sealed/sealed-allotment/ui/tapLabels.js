(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { tapLabels: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { clampLabel } = require('../lib/clamp.js');
  const { factor } = require('../lib/scale.js');
  const { labelWidth } = require('../lib/text.js');

  function labels(taps, viewport) {
    const f = factor(viewport);
    return taps.map(t => { const text = 'Tap ' + t.id; const w = labelWidth(text); return { text, x: clampLabel(t.x * f + 6, w), y: t.y * f, w }; });
  }

  return { labels };
});
