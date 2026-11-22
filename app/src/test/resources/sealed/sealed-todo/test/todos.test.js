const test = require('node:test');
const assert = require('assert');
const { add, toggle, byDue } = require('../lib/todos.js');

test('add and toggle', () => {
  const list = [];
  const id = add(list, 'A', ['x'], '2026-01-01');
  toggle(list, id);
  assert.strictEqual(list[0].done, true);
});

test('dated tasks sort earliest first', () => {
  const list = [{ id: 1, due: '2026-03-02' }, { id: 2, due: '2026-03-01' }];
  assert.deepStrictEqual(byDue(list).map(t => t.id), [2, 1]);
});
