(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { importBooks: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Reads the old app's CSV export into books for the catalogue. Not written yet.
  function importBooks(text) {
    throw new Error('Import is not written yet');
  }

  return { importBooks };
});
