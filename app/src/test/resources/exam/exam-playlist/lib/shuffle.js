(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { shuffle: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // a shuffled copy of songs; when `last` (the song that just played) would come first, it is swapped with the next one
  function shuffled(songs, last, rng) {
    const r = rng || Math.random;
    const out = songs.slice();
    for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(r() * (i + 1)); const t = out[i]; out[i] = out[j]; out[j] = t; }
    if (out.length > 1 && out[0] === last) { const t = out[0]; out[0] = out[1]; out[1] = t; }
    return out;
  }
  return { shuffled };
});
