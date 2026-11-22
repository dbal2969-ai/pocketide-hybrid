// The list of harbours on the chart screen, by region and then by name.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { harbourList: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { sortBy } = require('../util/sortBy.js');
  const dom = require('./dom.js');

  function rows(harbours) {
    return sortBy(harbours, ['region', 'name']).map(h => ({ id: h.id, text: h.region + ' · ' + h.name }));
  }

  function render(doc, harbours, onPick) {
    return dom.list(doc, 'harbours', rows(harbours), onPick);
  }

  return { rows, render };
});
