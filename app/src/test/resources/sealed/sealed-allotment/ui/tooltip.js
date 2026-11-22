(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { tooltip: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { clampLabel } = require('../lib/clamp.js');
  const { labelWidth } = require('../lib/text.js');

  // the box shown when a plot is tapped, beside the finger
  function place(text, fingerX, fingerY, viewport) {
    const w = Math.max(120, labelWidth(text));
    return { text, x: clampLabel(fingerX + 12, w), y: fingerY + 12, w };
  }

  return { place };
});
