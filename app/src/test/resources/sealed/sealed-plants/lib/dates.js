(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { dates: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const DAY = 86400000;
  const parse = iso => { const [y, m, d] = iso.split('-').map(Number); return Date.UTC(y, m - 1, d); };
  const iso = ms => new Date(ms).toISOString().slice(0, 10);
  const addDays = (isoDate, n) => iso(parse(isoDate) + n * DAY);

  // how a date is shown on the planner
  function show(isoDate) {
    return isoDate;
  }

  return { DAY, parse, iso, addDays, show };
});
