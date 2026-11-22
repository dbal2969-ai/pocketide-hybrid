// What happens at sea: each day of a voyage may bring a storm, sickness, calm weather or spoiled food.
// roll() decides the events for a voyage from its seed; apply() makes them happen to the voyage.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { events: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const random = require('../util/random.js');
  const crew = require('./crew.js');

  function roll(defs, days, seed) {
    const rng = random.seeded(seed);
    const out = [];
    for (let day = 1; day <= days; day++) {
      for (const d of defs) {
        if (day < d.afterDay || !random.chance(rng, d.perDay)) continue;
        const e = { day, type: d.type, text: d.text };
        if (d.lost) e.lost = random.between(rng, d.lost[0], d.lost[1]);
        if (d.days) e.days = random.between(rng, d.days[0], d.days[1]);
        if (d.goods && d.goods.length) { e.goods = d.goods; e.share = d.share; }
        out.push(e);
      }
    }
    return out;
  }

  function spoil(v, e) {
    const gone = {};
    for (const id of e.goods || []) {
      const have = v.hold[id] || 0;
      const n = Math.floor(have * e.share);
      if (n > 0) { v.hold[id] = have - n; gone[id] = n; }
    }
    return gone;
  }

  // Applies the events in day order. Storms take men overboard at once; sickness is counted over the voyage and the sick
  // are put ashore together at the end, so the log shows one line for them.
  function apply(v, events) {
    const lines = [];
    let sick = 0;
    let extraDays = 0;
    for (const e of events.slice().sort((a, b) => a.day - b.day)) {
      if (e.type === 'storm') {
        const gone = crew.loseCrew(v.crew, e.lost || 0);
        if (gone) lines.push('Day ' + e.day + ': ' + e.text + ' (' + gone + ' lost)');
      } else if (e.type === 'sickness') {
        sick += e.lost || 0;
      } else if (e.type === 'calm') {
        extraDays += e.days || 0;
        lines.push('Day ' + e.day + ': ' + e.text + ' for ' + e.days + ' days');
      } else if (e.type === 'spoil') {
        const gone = spoil(v, e);
        const what = Object.entries(gone).map(([id, n]) => n + ' ' + id).join(', ');
        if (what) lines.push('Day ' + e.day + ': ' + e.text + ' (' + what + ')');
      }
    }
    if (sick > 0) {
      v.crew.count -= sick;
      v.crew.lost += sick;
      lines.push(sick + ' sick put ashore');
    }
    v.day += extraDays;
    v.log.push(...lines);
    return { lines, extraDays, sick };
  }

  return { roll, apply, spoil };
});
