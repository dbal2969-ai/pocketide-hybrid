(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { historyView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { show } = require('../lib/dates.js');

  function rows(state) {
    return state.history.slice().reverse().map(h => {
      const p = state.plants.find(x => x.id === h.plantId);
      return show(h.date) + ' \u00b7 ' + (p ? p.name : h.plantId);
    });
  }

  return { rows };
});
