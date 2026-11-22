(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { weekView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { thisWeek } = require('../lib/schedule.js');
  const { show } = require('../lib/dates.js');

  function rows(plants, groups, today) {
    return thisWeek(plants, groups, today).map(x => x.plant.name + ' \u00b7 ' + show(x.next));
  }

  return { rows };
});
