(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { gallery: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { captionText } = require('../lib/caption.js');

  // [{ src, text }] for the grid; the page puts text in with textContent
  function items(album) {
    return album.photos.map(p => ({ src: p.file, text: captionText(p) }));
  }

  return { items };
});
