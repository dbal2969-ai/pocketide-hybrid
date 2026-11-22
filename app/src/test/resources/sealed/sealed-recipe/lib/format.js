(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { format: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // money for display: the symbol from the settings, two decimals, optionally rounded up to the next 5p
  function money(n, settings) {
    const s = settings || {};
    let v = n;
    if (s.roundTo5p) v = Math.ceil(Math.round(v * 100) / 5) * 5 / 100;
    return (s.symbol || '\u00a3') + v.toFixed(2);
  }

  function qty(n, unit) {
    return unit === 'each' ? String(n) : n + ' ' + unit;
  }

  return { money, qty };
});
