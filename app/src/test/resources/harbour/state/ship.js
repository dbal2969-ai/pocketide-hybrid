// The ships the player can sail, and the choice of one.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { ship: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  function choose(ships, id) {
    const s = ships[id];
    if (!s) throw new Error('no ship called ' + id);
    return s;
  }

  // The fastest ship that can carry the cargo.
  function fastestFor(ships, cargoWeight) {
    const ok = Object.values(ships).filter(s => s.capacity >= cargoWeight);
    if (!ok.length) return null;
    return ok.reduce((a, b) => (b.speed > a.speed ? b : a));
  }

  return { choose, fastestFor };
});
