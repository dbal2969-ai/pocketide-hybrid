(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { listView: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function line(t) {
    return (t.done ? '\u2713 ' : '') + t.text + (t.tags.length ? ' ' + t.tags.map(g => '#' + g).join(' ') : '') + (t.due ? ' \u00b7 ' + t.due : '');
  }

  return { line };
});
