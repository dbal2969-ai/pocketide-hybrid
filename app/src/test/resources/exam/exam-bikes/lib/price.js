(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { price: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const PER_HOUR = 4;
  const DAY_CAP = 20;

  // minutes out -> what to pay (each started hour is charged; never more than the day cap)
  function cost(minutes) {
    if (minutes <= 0) return 0;
    const hours = Math.floor(minutes / 60) || 1;
    return Math.min(hours * PER_HOUR, DAY_CAP);
  }

  return { PER_HOUR, DAY_CAP, cost };
});
