(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { units: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function show(celsius, unit) {
    if (unit === 'F') return (Math.round((celsius * 9 / 5 + 32) * 10) / 10) + ' \u00b0F';
    return (Math.round(celsius * 10) / 10) + ' \u00b0C';
  }
  return { show };
});
