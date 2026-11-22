(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { table: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function rows(list) {
    return list.map(t => [t.day, t.type, t.time, t.height.toFixed(1) + ' m']);
  }

  function render(doc, list) {
    const tb = doc.getElementById('tides');
    tb.innerHTML = '';
    for (const r of rows(list)) {
      const tr = doc.createElement('tr');
      for (const c of r) { const td = doc.createElement('td'); td.textContent = c; tr.append(td); }
      tb.append(tr);
    }
  }

  return { rows, render };
});
