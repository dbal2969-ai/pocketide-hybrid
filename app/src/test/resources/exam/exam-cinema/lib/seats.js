(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { seats: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const ROWS = ['A', 'B', 'C', 'D', 'E', 'F'];
  const PER_ROW = 10;

  function create() {
    const taken = {};
    for (const r of ROWS) taken[r] = new Array(PER_ROW).fill(false);
    return { taken };
  }

  // seat numbers are 1..PER_ROW; returns true when booked, false when taken or not a seat
  function book(hall, row, n) {
    const r = hall.taken[row];
    if (!r || n < 1 || n >= PER_ROW) return false;
    if (r[n - 1]) return false;
    r[n - 1] = true;
    return true;
  }

  function free(hall, row) {
    return hall.taken[row].filter(t => !t).length;
  }

  return { ROWS, PER_ROW, create, book, free };
});
