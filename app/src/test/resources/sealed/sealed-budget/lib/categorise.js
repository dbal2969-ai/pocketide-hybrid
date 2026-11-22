(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { categorise: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the first rule whose word is in the description, case ignored; 'Other' when none is
  function categoryOf(description, rules) {
    const d = description.toLowerCase();
    const r = rules.find(x => d.includes(x.word.toLowerCase()));
    return r ? r.category : 'Other';
  }

  return { categoryOf };
});
