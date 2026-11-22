(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { rota: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the singer for practice number n (0, 1, 2 ...), taking turns in the order given
  function whoFor(singers, n) {
    if (!singers.length) return null;
    return singers[n % (singers.length - 1 || 1)];
  }

  // the next k practices from practice number `from`
  function next(singers, from, k) {
    const out = [];
    for (let i = 0; i < k; i++) out.push(whoFor(singers, from + i));
    return out;
  }

  return { whoFor, next };
});
