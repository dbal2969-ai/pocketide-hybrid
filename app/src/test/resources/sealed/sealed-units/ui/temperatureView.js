(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { temperatureView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { celsiusTo, toCelsius } = require('../lib/convert.js');
  const { show } = require('../lib/show.js');

  function result(value, from, to, settings) {
    return show(celsiusTo(toCelsius(value, from), to), settings.places) + ' \u00b0' + to;
  }

  return { result };
});
