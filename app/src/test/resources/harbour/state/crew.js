// The crew of a ship: how many are aboard, what they are paid, and how many were lost on the way.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { crew: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { round2 } = require('../util/money.js');

  function newCrew(ship) {
    return { ship: ship.id, count: ship.crew, max: ship.crew, wage: ship.wage, lost: 0, morale: 70 };
  }

  function loseCrew(crew, n) {
    const gone = Math.min(Math.max(0, crew.count), Math.max(0, n));
    crew.count -= gone;
    crew.lost += gone;
    crew.morale = Math.max(0, crew.morale - gone * 5);
    return gone;
  }

  function wagesFor(crew, days) {
    return round2(Math.max(0, crew.count) * crew.wage * days);
  }

  function hire(crew, n) {
    const room = Math.max(0, crew.max - crew.count);
    const taken = Math.min(room, Math.max(0, n));
    crew.count += taken;
    return taken;
  }

  // Short-handed ships sail slower: below half the crew, a day of sailing takes longer.
  function slowdown(crew) {
    if (crew.max === 0) return 1;
    const share = crew.count / crew.max;
    return share >= 0.5 ? 1 : share > 0 ? 1.5 : Infinity;
  }

  function cheer(crew, amount) {
    crew.morale = Math.min(100, crew.morale + amount);
  }

  return { newCrew, loseCrew, wagesFor, hire, slowdown, cheer };
});
