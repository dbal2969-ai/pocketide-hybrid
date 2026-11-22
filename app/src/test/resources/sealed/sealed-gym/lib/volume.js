(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { volume: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // volume = reps x kg, summed
  function setVolume(set) {
    return set.reps * set.kg;
  }

  function sessionVolume(session) {
    return session.exercises.reduce((s, e) => s + e.sets.reduce((t, set) => t + setVolume(set), 0), 0);
  }

  return { setVolume, sessionVolume };
});
