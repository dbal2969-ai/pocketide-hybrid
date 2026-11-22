const test = require('node:test');
const assert = require('assert');
const { create, book, free } = require('../lib/seats.js');

test('a seat is booked once', () => {
  const h = create();
  assert.strictEqual(book(h, 'B', 7), true);
  assert.strictEqual(book(h, 'B', 7), false);
  assert.strictEqual(free(h, 'B'), 9);
});
