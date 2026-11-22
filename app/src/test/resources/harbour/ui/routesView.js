// The routes screen: where the ship can go from here and how long it takes, nearest first.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { routesView: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const routes = require('../state/routes.js');
  const format = require('../util/format.js');
  const dom = require('./dom.js');

  function rows(v, harbours) {
    return routes.routesFrom(v.at, harbours, v.ship, v.crew).map(r => ({ id: r.to.id, text: r.to.name + ' · ' + r.leagues + ' leagues · ' + format.days(r.days) }));
  }

  function render(doc, v, harbours, onPick) {
    return dom.list(doc, 'routes', rows(v, harbours), onPick);
  }

  return { rows, render };
});
