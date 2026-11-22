// A voyage: the ship, its crew, the day, the gold, where it is, and the cargo in the hold.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { voyage: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { round2 } = require('../util/money.js');
  const crew = require('./crew.js');

  function newVoyage(ship, gold, harbour) {
    return { ship, crew: crew.newCrew(ship), gold: round2(gold), day: 0, at: harbour, hold: {}, log: [] };
  }

  function weightOf(goods, id) {
    const g = goods.find(x => x.id === id);
    return g ? g.weight : 0;
  }

  function holdUsed(v, goods) {
    return Object.entries(v.hold).reduce((s, [id, qty]) => s + qty * weightOf(goods, id), 0);
  }

  function spaceLeft(v, goods) {
    return v.ship.capacity - holdUsed(v, goods);
  }

  function load(v, good, qty) {
    v.hold[good.id] = (v.hold[good.id] || 0) + qty;
    return v;
  }

  function sail(v, days) {
    v.day += days;
    v.log.push('Sailed ' + days + ' day' + (days === 1 ? '' : 's'));
    return v;
  }

  // Wages are paid on arrival for the days at sea.
  function arrive(v, harbour, days, book) {
    const wages = crew.wagesFor(v.crew, days);
    v.gold = round2(v.gold - wages);
    v.at = harbour;
    v.log.push('Paid ' + wages + ' in wages at ' + harbour.name);
    if (book) book.entries.push({ day: v.day, kind: 'wages', what: harbour.name, amount: wages });
    return wages;
  }

  return { newVoyage, holdUsed, spaceLeft, load, sail, arrive };
});
