(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { config: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // seconds per question
  const SECONDS_PER_QUESTION = 20;
  return { SECONDS_PER_QUESTION };
});
