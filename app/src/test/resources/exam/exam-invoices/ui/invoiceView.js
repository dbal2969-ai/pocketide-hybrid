(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { invoiceView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { gross, total } = require('../lib/lines.js');
  function rows(lines) {
    return lines.map(l => l.text + ': ' + gross(l).toFixed(2)).concat(['Total: ' + total(lines).toFixed(2)]);
  }
  return { rows };
});
