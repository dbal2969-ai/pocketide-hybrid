// Routes between harbours: the distance on the chart and the days a ship needs to sail it.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { routes: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { sortBy } = require('../util/sortBy.js');
  const crew = require('./crew.js');

  const LEAGUES_PER_UNIT = 0.1;

  function distance(a, b) {
    const dx = a.x - b.x, dy = a.y - b.y;
    return Math.round(Math.sqrt(dx * dx + dy * dy) * LEAGUES_PER_UNIT);
  }

  // Days at sea for a ship with this crew: speed is leagues a day, slowed when short-handed.
  function daysFor(from, to, ship, c) {
    const d = distance(from, to);
    if (d === 0) return 0;
    const slow = c ? crew.slowdown(c) : 1;
    if (!Number.isFinite(slow)) return Infinity;
    return Math.max(1, Math.ceil(d / ship.speed * slow));
  }

  function routesFrom(from, harbours, ship, c) {
    const out = harbours.filter(h => h.id !== from.id).map(h => ({ to: h, leagues: distance(from, h), days: daysFor(from, h, ship, c) }));
    return sortBy(out, [r => r.days, r => r.to.name]);
  }

  return { distance, daysFor, routesFrom };
});
