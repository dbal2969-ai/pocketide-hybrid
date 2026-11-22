(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { weeks: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { DAY, parse, iso, monday } = require('./dates.js');
  const { sessionVolume } = require('./volume.js');

  // the sessions of the week that starts on `start` (a Monday, ms)
  function inWeek(sessions, start) {
    const end = start + 7 * DAY;
    return sessions.filter(s => { const t = parse(s.date); return t >= start && t <= end; });
  }

  // [{ week: 'YYYY-MM-DD' (its Monday), volume, sessions }] from the first week to the last
  function weeklyTotals(sessions) {
    if (!sessions.length) return [];
    const times = sessions.map(s => parse(s.date));
    const first = monday(Math.min.apply(null, times));
    const last = monday(Math.max.apply(null, times));
    const out = [];
    for (let w = first; w <= last; w += 7 * DAY) {
      const these = inWeek(sessions, w);
      out.push({ week: iso(w), volume: these.reduce((s, x) => s + sessionVolume(x), 0), sessions: these.length });
    }
    return out;
  }

  return { inWeek, weeklyTotals };
});
