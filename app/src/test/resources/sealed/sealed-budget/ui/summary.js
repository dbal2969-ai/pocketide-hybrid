(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { summary: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function rows(totals) {
    return Object.keys(totals).sort().map(c => c + ': ' + (totals[c] < 0 ? '-' : '') + '\u00a3' + Math.abs(totals[c]).toFixed(2));
  }

  return { rows };
});
