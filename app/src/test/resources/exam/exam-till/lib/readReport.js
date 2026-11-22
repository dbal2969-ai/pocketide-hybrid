(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { readReport: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Reads the till's fixed-width report. Not written yet.
  function readReport(text) {
    return [];
  }

  return { readReport };
});
