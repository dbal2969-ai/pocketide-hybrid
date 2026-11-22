// Saving and loading a game. The save is plain JSON; the seed rebuilds the world.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { save: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  const VERSION = 2;

  function toSave(v, seed, book) {
    return JSON.stringify({
      version: VERSION, seed, ship: v.ship.id, gold: v.gold, day: v.day, at: v.at.id, hold: v.hold,
      crew: { count: v.crew.count, lost: v.crew.lost, morale: v.crew.morale },
      ledger: book ? book.entries : [],
    });
  }

  function fromSave(text) {
    const s = JSON.parse(text);
    if (s.version === 1) { s.crew = null; s.ledger = []; s.version = VERSION; }
    if (s.version !== VERSION) throw new Error('save version ' + s.version + ' is not supported');
    return s;
  }

  // Rebuilds the voyage from a save, against the world it was made in.
  function restore(s, world, voyageApi) {
    const ship = world.ships[s.ship];
    const at = world.harbours.find(h => h.id === s.at);
    if (!ship || !at) throw new Error('the save names a ship or harbour this game does not have');
    const v = voyageApi.newVoyage(ship, s.gold, at);
    v.day = s.day;
    v.hold = Object.assign({}, s.hold);
    if (s.crew) Object.assign(v.crew, s.crew);
    return v;
  }

  return { VERSION, toSave, fromSave, restore };
});
