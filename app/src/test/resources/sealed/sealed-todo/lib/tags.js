(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { tags: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // every tag in use, in order of first use
  function allTags(todos) {
    const out = [];
    for (const t of todos) for (const g of t.tags) if (!out.includes(g)) out.push(g);
    return out;
  }

  function withTag(todos, tag) {
    return todos.filter(t => t.tags.includes(tag));
  }

  return { allTags, withTag };
});
