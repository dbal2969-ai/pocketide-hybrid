const test = require('node:test');
const assert = require('assert');
const { create, stamp, line } = require('../lib/card.js');

test('a stamp counts', () => {
  const c = create();
  stamp(c);
  assert.strictEqual(c.stamps, 1);
  assert.match(line(c), /^1 of 10/);
});

test('the tenth coffee is free', () => {
  const c = create();
  for (let i = 0; i < 10; i++) stamp(c);
  assert.deepStrictEqual(c, { stamps: 0, free: 1 });
});
