(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { calendar: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // seeds to sow in a month (1..12)
  function sowIn(seeds, month) { return seeds.filter(s => s.from <= month && month <= s.to).map(s => s.name); }
  return { sowIn };
});
