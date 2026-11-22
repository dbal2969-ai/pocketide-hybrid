const test = require('node:test');
const assert = require('assert');
const { addAll, search, sorted } = require('../lib/catalogue.js');

test('the same book is not added twice', () => {
  const list = [];
  assert.strictEqual(addAll(list, [{ title: 'A', author: 'X', year: 2000, isbn: '1' }, { title: 'A', author: 'X', year: 2000, isbn: '1' }]), 1);
});

test('search and sort', () => {
  const list = [{ title: 'Salt', author: 'Rowe', year: 2001, isbn: '2' }, { title: 'Moth', author: 'Achebe', year: 1999, isbn: '3' }];
  assert.strictEqual(search(list, 'row').length, 1);
  assert.deepStrictEqual(sorted(list, 'year').map(b => b.title), ['Moth', 'Salt']);
});
