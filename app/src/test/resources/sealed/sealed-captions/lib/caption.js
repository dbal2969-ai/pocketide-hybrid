(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { caption: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { escapeHtml } = require('./escape.js');

  // the caption the screens show under a photo; 'Untitled' when there is none
  function captionText(photo) {
    const c = (photo.caption || '').trim();
    return c ? escapeHtml(c) : 'Untitled';
  }

  return { captionText };
});
