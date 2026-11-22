// Money helpers. Amounts are coins with two decimals ("12.50").
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { money: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  function round2(n) {
    return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
  }

  function formatMoney(n) {
    const v = round2(n);
    const sign = v < 0 ? '-' : '';
    const [whole, cents] = Math.abs(v).toFixed(2).split('.');
    return sign + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + cents;
  }

  // "1,250.50", "12", "0.75" -> numbers; anything else -> NaN
  function parseMoney(text) {
    if (typeof text === 'number') return text;
    const s = String(text == null ? '' : text).replace(/,/g, '').trim();
    if (!/^-?\d+(\.\d+)?$/.test(s)) return NaN;
    return parseFloat(s);
  }

  function sumMoney(values) {
    return round2(values.reduce((a, b) => a + Number(b || 0), 0));
  }

  // percent off: 20 -> the amount taken off [amount]
  function percentOf(amount, percent) {
    return round2(amount * percent / 100);
  }

  return { round2, formatMoney, parseMoney, sumMoney, percentOf };
});
