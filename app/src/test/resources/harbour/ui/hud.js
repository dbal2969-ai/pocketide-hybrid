// The top bar: the ship, the day, the gold and the crew.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { hud: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { formatMoney } = require('../util/money.js');

  function text(v) {
    return [
      v.ship.name,
      'day ' + v.day,
      formatMoney(v.gold) + ' gold',
      'crew ' + v.crew.count + ' of ' + v.crew.max,
    ].join('  ·  ');
  }

  return { text };
});
