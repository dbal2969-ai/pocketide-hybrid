// Labels shared by the screens.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { format: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  function days(n) {
    if (!Number.isFinite(n)) return 'cannot sail';
    return n + ' day' + (n === 1 ? '' : 's');
  }

  function weight(n) {
    return n + ' t';
  }

  function percentLabel(fraction) {
    return Math.round(fraction * 100) + '%';
  }

  function plural(n, word) {
    return n + ' ' + word + (n === 1 ? '' : 's');
  }

  return { days, weight, percentLabel, plural };
});
