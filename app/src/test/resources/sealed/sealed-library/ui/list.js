(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { list: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function line(b) {
    return b.title + ' \u2014 ' + b.author + ' (' + b.year + ')';
  }

  function render(doc, books) {
    const ul = doc.getElementById('books');
    ul.innerHTML = '';
    for (const b of books) { const li = doc.createElement('li'); li.textContent = line(b); ul.append(li); }
    doc.getElementById('count').textContent = books.length + ' books';
  }

  return { line, render };
});
