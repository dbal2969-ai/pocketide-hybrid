(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { budget: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  const { categoryOf } = require('./categorise.js');

  // { category: total } for lines { date, description, amount }; spending is negative
  function byCategory(lines, rules) {
    const out = {};
    for (const l of lines) {
      const c = categoryOf(l.description, rules);
      out[c] = Math.round(((out[c] || 0) + l.amount) * 100) / 100;
    }
    return out;
  }

  return { byCategory };
});
