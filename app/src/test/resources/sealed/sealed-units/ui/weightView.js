(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { weightView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { convert } = require('../lib/convert.js');
  const { show } = require('../lib/show.js');

  function result(value, from, to, factors, settings) {
    return show(convert(value, from, to, factors.weight), settings.places) + ' ' + to;
  }

  return { result };
});
