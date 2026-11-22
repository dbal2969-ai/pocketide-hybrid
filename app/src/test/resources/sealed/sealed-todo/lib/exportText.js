(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require);
  else root.App = Object.assign(root.App || {}, { exportText: factory(p => root.App[p.split('/').pop().replace(/\.js$/, '')]) });
})(this, function (require) {
  'use strict';
  // the list as plain text, one line each: "[x] Send invoice (due 2026-03-05)"
  function toText(todos) {
    return todos.map(t => '[' + (t.done ? 'x' : ' ') + '] ' + t.text + (t.due ? ' (due ' + t.due + ')' : '')).join('\n') + '\n';
  }

  return { toText };
});
