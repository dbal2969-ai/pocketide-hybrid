const test = require('node:test');
const assert = require('assert');
const { tally } = require('../lib/score.js');

test('right answers are counted', () => {
  const played = [{ correct: 0 }, { correct: 2 }];
  assert.deepStrictEqual(tally(played, [0, 1]), { right: 1, of: 2 });
});
