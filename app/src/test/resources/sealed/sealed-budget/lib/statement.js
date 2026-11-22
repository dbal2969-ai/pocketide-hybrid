(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { statement: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // Reads a pasted bank statement into lines { date: 'YYYY-MM-DD', description, amount (number) }. Not written yet.
  function readStatement(text) {
    return [];
  }

  return { readStatement };
});
