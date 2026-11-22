(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { convert: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // value in `from` units -> `to` units, by the factors to the base unit
  function convert(value, from, to, table) {
    if (!(from in table) || !(to in table)) throw new Error('unknown unit ' + (from in table ? to : from));
    return value * table[from] / table[to];
  }

  function celsiusTo(value, to) {
    return to === 'F' ? value * 9 / 5 + 32 : to === 'K' ? value + 273.15 : value;
  }

  function toCelsius(value, from) {
    return from === 'F' ? (value - 32) * 5 / 9 : from === 'K' ? value - 273.15 : value;
  }

  return { convert, celsiusTo, toCelsius };
});
