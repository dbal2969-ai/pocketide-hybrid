// A whole trip from one harbour to another: the days, the events on the way, the wages on arrival.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { travel: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const routes = require('./routes.js');
  const events = require('./events.js');
  const voyage = require('./voyage.js');

  function travel(v, to, defs, seed, book) {
    const days = routes.daysFor(v.at, to, v.ship, v.crew);
    if (!Number.isFinite(days)) return { ok: false, why: 'no crew to sail the ship' };
    const evs = events.roll(defs, days, seed + v.day);
    voyage.sail(v, days);
    const result = events.apply(v, evs);
    const wages = voyage.arrive(v, to, days + result.extraDays, book);
    return { ok: true, days: days + result.extraDays, events: result.lines, wages };
  }

  return { travel };
});
