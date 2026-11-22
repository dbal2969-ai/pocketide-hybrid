(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { tides: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the tides at or after `time` ('HH:MM') on `day`, then the following days, in the page's order
  function upcoming(list, day, time) {
    const i = list.findIndex(t => t.day === day && t.time >= time);
    return i < 0 ? [] : list.slice(i);
  }

  function highest(list) {
    return list.filter(t => t.type === 'High').reduce((a, b) => (a && a.height >= b.height ? a : b), null);
  }

  return { upcoming, highest };
});
