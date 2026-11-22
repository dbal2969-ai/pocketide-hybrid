// Loads the data files: from disk under node (tests, tools), with fetch in the browser.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { load: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { normaliseAll } = require('./normalise.js');

  const FILES = { goods: 'goods.json', harbours: 'harbours.json', offers: 'offers.json', ships: 'ships.json', events: 'events.json' };

  function loadSync(dir) {
    const fs = require('fs');
    const path = require('path');
    const base = dir || path.join(__dirname, '..', 'data');
    const raw = {};
    for (const [k, f] of Object.entries(FILES)) raw[k] = JSON.parse(fs.readFileSync(path.join(base, f), 'utf8'));
    return normaliseAll(raw);
  }

  async function loadFetch(base) {
    const raw = {};
    await Promise.all(Object.entries(FILES).map(async ([k, f]) => {
      const r = await fetch((base || 'data/') + f);
      if (!r.ok) throw new Error('could not load ' + f + ' (' + r.status + ')');
      raw[k] = await r.json();
    }));
    return normaliseAll(raw);
  }

  return { FILES, loadSync, loadFetch };
});
