(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { show: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // a result for the screen, to `places` decimal places, without trailing zeros
  function show(n, places) {
    const f = Math.pow(10, places);
    return String(Math.round(n * f / f));
  }

  return { show };
});
