// Turns the raw JSON in data/ into the shapes the game uses. The data files keep numbers as strings (they are edited in a
// spreadsheet and exported), so every field is converted here, once, by the schema, and the rest of the game can trust the types.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { normalise: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { convert } = require('../util/convert.js');
  const schema = require('./schema.js');

  // One row by its schema. Problems are collected, not thrown, so a typo in one row doesn't stop the game.
  function row(raw, shape, where, problems) {
    const out = {};
    for (const [field, spec] of Object.entries(shape)) {
      let v = convert(spec.type, raw[field], spec.fallback);
      if (spec.required && (v === '' || v == null)) problems.push(where + ': ' + field + ' is missing');
      if (typeof v === 'number') {
        if (spec.min != null && v < spec.min) { problems.push(where + ': ' + field + ' ' + v + ' is below ' + spec.min); v = spec.min; }
        if (spec.max != null && v > spec.max) { problems.push(where + ': ' + field + ' ' + v + ' is above ' + spec.max); v = spec.max; }
      }
      out[field] = v;
    }
    return out;
  }

  function normaliseGood(raw, problems) {
    const g = row(raw, schema.GOOD, 'goods/' + (raw.id || '?'), problems || []);
    if (g.name == null) g.name = g.id;
    return g;
  }

  function normaliseHarbour(raw, problems) {
    const h = row(raw, schema.HARBOUR, 'harbours/' + (raw.id || '?'), problems || []);
    if (h.name == null) h.name = h.id;
    return h;
  }

  function normaliseOffer(raw, problems) {
    return row(raw, schema.OFFER, 'offers/' + (raw.id || '?'), problems || []);
  }

  function normaliseShips(raw, problems) {
    const out = {};
    for (const [id, s] of Object.entries(raw || {})) {
      const ship = row(s, schema.SHIP, 'ships/' + id, problems || []);
      ship.id = id;
      if (ship.name == null) ship.name = id;
      out[id] = ship;
    }
    return out;
  }

  function normaliseEvents(raw, problems) {
    return (raw || []).map((e, i) => row(e, schema.EVENT, 'events/' + (e.type || i), problems || []));
  }

  function normaliseAll(raw) {
    const problems = [];
    const goods = (raw.goods || []).map(g => normaliseGood(g, problems));
    const harbours = (raw.harbours || []).map(h => normaliseHarbour(h, problems));
    const ids = new Set(goods.map(g => g.id));
    const offers = (raw.offers || []).map(o => normaliseOffer(o, problems)).filter(o => {
      if (ids.has(o.good)) return true;
      problems.push('offers/' + o.id + ': no good called ' + o.good);
      return false;
    });
    for (const h of harbours) {
      for (const m of h.makes) if (!ids.has(m)) problems.push('harbours/' + h.id + ': makes an unknown good ' + m);
    }
    const seen = new Set();
    for (const g of goods) {
      if (seen.has(g.id)) problems.push('goods/' + g.id + ': listed twice');
      seen.add(g.id);
    }
    return { goods, harbours, offers, ships: normaliseShips(raw.ships, problems), events: normaliseEvents(raw.events, problems), problems };
  }

  return { row, normaliseGood, normaliseHarbour, normaliseOffer, normaliseShips, normaliseEvents, normaliseAll };
});
