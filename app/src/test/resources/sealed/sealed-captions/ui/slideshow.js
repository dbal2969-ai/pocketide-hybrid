(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { slideshow: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { captionText } = require('../lib/caption.js');

  // the slides, each with the album title and the caption as one line
  function slides(album) {
    return album.photos.map(p => ({ src: p.file, title: album.title + ' \u2014 ' + captionText(p) }));
  }

  return { slides };
});
