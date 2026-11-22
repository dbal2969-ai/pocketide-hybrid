(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { tabs: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the tabs along the top, in order: id and label
  const TABS = [
    { id: 'tin', label: 'Seed tin' },
    { id: 'notes', label: 'Notes' },
  ];
  return { TABS };
});
