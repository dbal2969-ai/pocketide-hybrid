(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { order: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the line printed on the baker's sheet
  function toLine(o) {
    return o.cake + ' (' + o.size + ') for ' + o.day;
  }
  return { toLine };
});
