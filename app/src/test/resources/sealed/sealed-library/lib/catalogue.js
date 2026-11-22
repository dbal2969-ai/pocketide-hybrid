(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { catalogue: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // books: { title, author, year (number), isbn (string, keeps leading zeros) }
  function key(b) {
    return b.isbn || (b.title + '|' + b.author).toLowerCase();
  }

  // adds books not already there (same ISBN, or same title and author); returns how many were added
  function addAll(list, books) {
    const seen = new Set(list.map(key));
    let n = 0;
    for (const b of books) {
      if (seen.has(key(b))) continue;
      seen.add(key(b));
      list.push(b);
      n++;
    }
    return n;
  }

  function search(list, q) {
    const s = q.trim().toLowerCase();
    return s ? list.filter(b => b.title.toLowerCase().includes(s) || b.author.toLowerCase().includes(s)) : list.slice();
  }

  function sorted(list, by) {
    return list.slice().sort((a, b) => by === 'year' ? a.year - b.year : a[by].localeCompare(b[by]));
  }

  return { key, addAll, search, sorted };
});
