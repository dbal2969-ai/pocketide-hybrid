(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { lightbox: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { captionText } = require('../lib/caption.js');

  // the big view of one photo: its caption and "3 of 4"
  function view(album, i) {
    return { src: album.photos[i].file, text: captionText(album.photos[i]), position: (i + 1) + ' of ' + album.photos.length };
  }

  return { view };
});
