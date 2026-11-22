// The receipt shown after buying at a harbour.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { receipt: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { formatMoney, round2 } = require('../util/money.js');
  const cart = require('../state/cart.js');

  function lines(c, offers) {
    const s = cart.summary(c, offers);
    const out = s.lines.map(l => l.qty + ' x ' + l.good.name + '  ' + formatMoney(l.good.price * l.qty));
    out.push('Subtotal  ' + formatMoney(s.subtotal));
    if (s.offer) out.push(s.offer.label + ' (' + s.offer.percent + '%)  -' + formatMoney(s.discount));
    out.push('Total  ' + formatMoney(round2(s.total - s.discount)));
    return out;
  }

  return { lines };
});
