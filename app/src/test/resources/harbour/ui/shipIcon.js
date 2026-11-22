// The ship icon on the board: placed by util/layout.js so it lines up with the HUD and the panels.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.Harbour = Object.assign(root.Harbour || {}, { shipIcon: factory(p => root.Harbour[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const layout = require('../util/layout.js');

  function style(viewport) {
    const b = layout.iconBox(viewport);
    return 'left:' + b.left + 'px;top:' + b.top + 'px;width:' + b.width + 'px;height:' + b.height + 'px';
  }

  return { style };
});
