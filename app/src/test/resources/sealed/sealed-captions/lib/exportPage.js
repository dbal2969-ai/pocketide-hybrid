(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { exportPage: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { escapeHtml } = require('./escape.js');

  // a stand-alone web page of the album (HTML source, so every caption is escaped)
  function toHtml(album) {
    const figs = album.photos.map(p => '<figure><img src="' + escapeHtml(p.file) + '" alt="' + escapeHtml(p.caption || '') + '">' +
      '<figcaption>' + escapeHtml(p.caption || 'Untitled') + '</figcaption></figure>').join('\n');
    return '<!doctype html>\n<title>' + escapeHtml(album.title) + '</title>\n<h1>' + escapeHtml(album.title) + '</h1>\n' + figs + '\n';
  }

  return { toHtml };
});
