(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { dates: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const DAY = 86400000;

  function parse(iso) {
    const [y, m, d] = iso.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
  }

  function iso(ms) {
    return new Date(ms).toISOString().slice(0, 10);
  }

  // the Monday that starts the week holding `ms`
  function monday(ms) {
    const dow = (new Date(ms).getUTCDay() + 6) % 7;
    return ms - dow * DAY;
  }

  return { DAY, parse, iso, monday };
});
