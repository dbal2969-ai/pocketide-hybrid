const test = require('node:test');
const assert = require('assert');
const { shuffled } = require('../lib/shuffle.js');

test('every song once', () => {
  assert.deepStrictEqual(shuffled(['a', 'b', 'c']).sort(), ['a', 'b', 'c']);
});

test('the last song never comes first', () => {
  for (let k = 0; k < 200; k++) assert.notStrictEqual(shuffled(['a', 'b', 'c'], 'b')[0], 'b');
});
