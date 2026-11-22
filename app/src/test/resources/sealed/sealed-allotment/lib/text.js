(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { text: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the width a label takes, 7 px a character plus padding
  function labelWidth(s) {
    return s.length * 7 + 8;
  }

  return { labelWidth };
});
