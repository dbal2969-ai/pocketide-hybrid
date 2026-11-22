(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { hallView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { ROWS, PER_ROW } = require('../lib/seats.js');

  // one line per row: "A  . . X . ..."
  function rows(hall) {
    return ROWS.map(r => r + '  ' + hall.taken[r].map(t => (t ? 'X' : '.')).join(' '));
  }

  return { rows, PER_ROW };
});
