(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { readSongs: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Reads a pasted song table into songs. Not written yet.
  function readSongs(text) {
    return [];
  }

  return { readSongs };
});
