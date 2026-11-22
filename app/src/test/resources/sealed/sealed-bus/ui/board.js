(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { board: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { hhmm } = require('../lib/time.js');
  const { nextDepartures } = require('../lib/next.js');

  // the board's rows: "in 6 min · 23:55"
  function rows(timetable, stopId, now) {
    return nextDepartures(timetable, stopId, now, 4).map(t => (t - now === 0 ? 'due' : 'in ' + (t - now) + ' min') + ' \u00b7 ' + hhmm(t));
  }

  function render(doc, timetable, stopId, now) {
    const ul = doc.getElementById('board');
    ul.innerHTML = '';
    for (const r of rows(timetable, stopId, now)) { const li = doc.createElement('li'); li.textContent = r; ul.append(li); }
  }

  return { rows, render };
});
