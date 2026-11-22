// Tiny DOM helpers for the screens (the screens' rows() functions are plain data, so they can be tested without a page).
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { dom: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  function list(doc, id, rows, onPick) {
    const ul = doc.getElementById(id);
    if (!ul) return null;
    ul.innerHTML = '';
    for (const r of rows) {
      const li = doc.createElement('li');
      li.textContent = r.text;
      if (r.id) li.dataset.id = r.id;
      if (onPick) li.addEventListener('click', () => onPick(r.id));
      ul.appendChild(li);
    }
    return ul;
  }

  function text(doc, id, value) {
    const el = doc.getElementById(id);
    if (el) el.textContent = value;
    return el;
  }

  function lines(doc, id, values) {
    const el = doc.getElementById(id);
    if (!el) return null;
    el.innerHTML = '';
    for (const v of values) {
      const div = doc.createElement('div');
      div.textContent = v;
      el.appendChild(div);
    }
    return el;
  }

  return { list, text, lines };
});
