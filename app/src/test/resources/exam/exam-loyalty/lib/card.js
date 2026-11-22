(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { card: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const FREE_AT = 10;

  function create() { return { stamps: 0, free: 0 }; }

  // a coffee bought: one more stamp; on the tenth, a free coffee and the card starts again
  function stamp(card) {
    card.stamps += 1;
    if (card.stamps >= FREE_AT) { card.free += 1; card.stamps = 0; }
    return card;
  }

  function line(card) { return card.stamps + ' of ' + FREE_AT + ' stamps' + (card.free ? ' \u00b7 ' + card.free + ' free to use' : ''); }

  return { FREE_AT, create, stamp, line };
});
