(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { settings: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // The settings screen's values, kept in localStorage in the browser.
  const DEFAULTS = { symbol: '\u00a3', roundTo5p: false };

  function load(store) {
    try {
      const raw = store && store.getItem('bake.settings');
      return Object.assign({}, DEFAULTS, raw ? JSON.parse(raw) : {});
    } catch (e) {
      return Object.assign({}, DEFAULTS);
    }
  }

  function save(store, s) {
    if (store) store.setItem('bake.settings', JSON.stringify(s));
  }

  return { DEFAULTS, load, save };
});
