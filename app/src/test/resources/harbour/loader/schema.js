// The shape of each data file: for every field, its type and the value used when a row leaves it out or gets it wrong.
// Types are the converters in util/convert.js.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { schema: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';

  const GOOD = {
    id: { type: 'text', fallback: '', required: true },
    name: { type: 'text', fallback: null },
    category: { type: 'text', fallback: 'Other' },
    price: { type: 'int', fallback: 0, min: 0 },
    weight: { type: 'int', fallback: 1, min: 1 },
  };

  const HARBOUR = {
    id: { type: 'text', fallback: '', required: true },
    name: { type: 'text', fallback: null },
    region: { type: 'text', fallback: 'Unknown' },
    tax: { type: 'percent', fallback: 0, min: 0 },
    x: { type: 'int', fallback: 0 },
    y: { type: 'int', fallback: 0 },
    makes: { type: 'list', fallback: [] },
  };

  const OFFER = {
    id: { type: 'text', fallback: '', required: true },
    good: { type: 'text', fallback: '', required: true },
    minQty: { type: 'int', fallback: 1, min: 1 },
    percent: { type: 'number', fallback: 0, min: 0, max: 99 },
    label: { type: 'text', fallback: 'Offer' },
  };

  const SHIP = {
    name: { type: 'text', fallback: null },
    capacity: { type: 'int', fallback: 0, min: 0 },
    crew: { type: 'int', fallback: 0, min: 0 },
    wage: { type: 'money', fallback: 0, min: 0 },
    speed: { type: 'int', fallback: 5, min: 1 },
  };

  const EVENT = {
    type: { type: 'text', fallback: '', required: true },
    text: { type: 'text', fallback: '' },
    lost: { type: 'range', fallback: null },
    days: { type: 'range', fallback: null },
    goods: { type: 'list', fallback: [] },
    share: { type: 'number', fallback: 0, min: 0, max: 1 },
    perDay: { type: 'number', fallback: 0, min: 0, max: 1 },
    afterDay: { type: 'int', fallback: 0, min: 0 },
  };

  return { GOOD, HARBOUR, OFFER, SHIP, EVENT };
});
