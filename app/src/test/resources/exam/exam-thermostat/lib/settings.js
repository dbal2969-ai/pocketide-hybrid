(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { settings: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function load(store) {
    try { return Object.assign({ unit: 'C' }, JSON.parse((store && store.getItem('thermo')) || '{}')); } catch (e) { return { unit: 'C' }; }
  }
  function save(store, s) { if (store) store.setItem('thermo', JSON.stringify(s)); }
  return { load, save };
});
