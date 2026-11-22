(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { schedule: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { addDays, parse, DAY } = require('./dates.js');

  // the next date a plant needs water: its last watering plus its group's interval
  function nextWatering(plant, groups) {
    const every = groups[plant.group];
    if (!every) throw new Error('no interval for group ' + plant.group);
    return addDays(plant.lastWatered, every);
  }

  // the plants due within 7 days of `today` (or overdue), soonest first
  function thisWeek(plants, groups, today) {
    const end = parse(today) + 7 * DAY;
    return plants.map(p => ({ plant: p, next: nextWatering(p, groups) }))
      .filter(x => parse(x.next) <= end)
      .sort((a, b) => parse(a.next) - parse(b.next));
  }

  return { nextWatering, thisWeek };
});
