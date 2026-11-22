(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { settingsView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // The settings panel: the currency symbol and rounding to 5p.
  function render(doc, settings, onChange) {
    const box = doc.getElementById('settings');
    box.innerHTML = '';
    const sym = doc.createElement('input');
    sym.value = settings.symbol;
    sym.setAttribute('aria-label', 'Currency symbol');
    sym.addEventListener('change', () => onChange(Object.assign({}, settings, { symbol: sym.value || '\u00a3' })));
    const round = doc.createElement('input');
    round.type = 'checkbox';
    round.checked = settings.roundTo5p;
    round.addEventListener('change', () => onChange(Object.assign({}, settings, { roundTo5p: round.checked })));
    const l1 = doc.createElement('label'); l1.append('Currency ', sym);
    const l2 = doc.createElement('label'); l2.append(round, ' Round prices up to 5p');
    box.append(l1, l2);
  }

  return { render };
});
