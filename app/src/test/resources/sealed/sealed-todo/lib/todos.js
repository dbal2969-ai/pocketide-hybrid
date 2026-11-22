(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { todos: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  function add(todos, text, tags, due) {
    const id = todos.reduce((m, t) => Math.max(m, t.id), 0) + 1;
    todos.push({ id, text, tags: tags || [], due: due || null, done: false });
    return id;
  }

  function toggle(todos, id) {
    const t = todos.find(x => x.id === id);
    if (t) t.done = !t.done;
  }

  // by due date, earliest first
  function byDue(todos) {
    return todos.slice().sort((a, b) => (a.due || '').localeCompare(b.due || ''));
  }

  return { add, toggle, byDue };
});
