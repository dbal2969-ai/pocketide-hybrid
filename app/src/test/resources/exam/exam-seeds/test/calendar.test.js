const test = require('node:test');
const assert = require('assert');
const { sowIn } = require('../lib/calendar.js');

test('what to sow in March', () => {
  assert.deepStrictEqual(sowIn([{ name: 'Carrot', from: 3, to: 6 }, { name: 'Squash', from: 4, to: 5 }], 3), ['Carrot']);
});
