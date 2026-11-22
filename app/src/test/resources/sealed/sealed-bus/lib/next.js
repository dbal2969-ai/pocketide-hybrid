(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { next: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the next departures from a stop: the route's start times plus that stop's offset, from `now` on, in order
  function at(timetable, stopId) {
    const stop = timetable.stops.find(s => s.id === stopId);
    if (!stop) throw new Error('no stop ' + stopId);
    return timetable.departures.map(d => d + stop.offset);
  }

  function nextDepartures(timetable, stopId, now, n) {
    return at(timetable, stopId).filter(t => t >= now).sort((a, b) => a - b).slice(0, n || 4);
  }

  return { at, nextDepartures };
});
