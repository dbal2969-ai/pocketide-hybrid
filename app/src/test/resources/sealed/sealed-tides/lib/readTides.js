(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { readTides: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Reads the harbour office's tide page into [{ day: 'Mon 2 Mar', type: 'High' | 'Low', time: 'HH:MM', height: metres (number) }].
  // Not written yet.
  function readTides(html) {
    return [];
  }

  return { readTides };
});
