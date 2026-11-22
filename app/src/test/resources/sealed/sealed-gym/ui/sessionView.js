(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { sessionView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { sessionVolume } = require('../lib/volume.js');

  function lines(session) {
    return [session.date + ' \u00b7 ' + sessionVolume(session) + ' kg'].concat(
      session.exercises.map(e => e.exercise + ': ' + e.sets.map(s => s.reps + 'x' + s.kg).join(', ')));
  }

  return { lines };
});
