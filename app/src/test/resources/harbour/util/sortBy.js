// Sorting by one or more keys. sortBy(list, ['region', 'name']) or sortBy(list, [r => r.days, 'name']).
// Used by the harbour list, the market, the hold and the routes.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { sortBy: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  function value(item, key) {
    const v = typeof key === 'function' ? key(item) : item[key];
    return typeof v === 'string' ? v.toLowerCase() : v;
  }

  function compare(a, b) {
    if (a === b) return 0;
    if (a === undefined || a === null) return 1;
    if (b === undefined || b === null) return -1;
    return a < b ? -1 : 1;
  }

  function byKey(key) {
    return (x, y) => compare(value(x, key), value(y, key));
  }

  // first, and where first finds them equal, second
  function then(first, second) {
    return (x, y) => first(x, y) || second;
  }

  function comparator(keys) {
    const ks = Array.isArray(keys) ? keys : [keys];
    return ks.slice(1).reduce((cmp, key) => then(cmp, byKey(key)), byKey(ks[0]));
  }

  function sortBy(list, keys) {
    return list.slice().sort(comparator(keys));
  }

  function descending(key) {
    return item => {
      const v = value(item, key);
      return typeof v === 'number' ? -v : v;
    };
  }

  return { sortBy, descending, comparator };
});
