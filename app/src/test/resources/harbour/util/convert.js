// Converters from the text the data files hold to the values the game uses. Each takes the raw value and a fallback.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { convert: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { parseMoney, round2 } = require('./money.js');

  function int(v, fallback) {
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : fallback;
  }

  function money(v, fallback) {
    const n = parseMoney(v);
    return Number.isFinite(n) ? round2(n) : fallback;
  }

  // "5" or "5%" -> 0.05
  function percent(v, fallback) {
    const n = parseFloat(String(v == null ? '' : v).replace('%', ''));
    return Number.isFinite(n) ? n / 100 : fallback;
  }

  function number(v, fallback) {
    const n = Number(v);
    return v !== '' && v != null && Number.isFinite(n) ? n : fallback;
  }

  function text(v, fallback) {
    const s = v == null ? '' : String(v).trim();
    return s || fallback;
  }

  function list(v, fallback) {
    return Array.isArray(v) ? v.map(String) : fallback;
  }

  function range(v, fallback) {
    if (!Array.isArray(v) || v.length !== 2) return fallback;
    const lo = int(v[0], NaN), hi = int(v[1], NaN);
    return Number.isFinite(lo) && Number.isFinite(hi) && lo <= hi ? [lo, hi] : fallback;
  }

  const CONVERTERS = { int, money, percent, number, text, list, range };

  function convert(type, v, fallback) {
    const f = CONVERTERS[type];
    if (!f) throw new Error('no converter for type ' + type);
    return f(v, fallback);
  }

  return { int, money, percent, number, text, list, range, convert, CONVERTERS };
});
