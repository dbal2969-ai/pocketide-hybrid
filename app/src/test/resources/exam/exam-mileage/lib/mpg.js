(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { mpg: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // miles per gallon of one fill-up
  function of(fill) {
    return fill.gallons > 0 ? fill.miles / fill.gallons : 0;
  }

  // the average over all fill-ups
  function average(fills) {
    if (!fills.length) return 0;
    return fills.reduce((s, f) => s + of(f), 0) / fills.length;
  }

  return { of, average };
});
