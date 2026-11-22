const test = require('node:test');
const assert = require('assert');
const { hhmm, serviceNow } = require('../lib/time.js');
const { nextDepartures } = require('../lib/next.js');

test('daytime minutes show as a clock time', () => {
  assert.strictEqual(hhmm(5 * 60 + 40), '05:40');
  assert.strictEqual(hhmm(23 * 60 + 59), '23:59');
});

test('just after midnight still counts as the day before', () => {
  assert.strictEqual(serviceNow(new Date(2026, 2, 3, 0, 20)), 1460);
});

test('departures come in order from now', () => {
  const tt = { stops: [{ id: 'a', offset: 0 }, { id: 'b', offset: 5 }], departures: [600, 630, 660] };
  assert.deepStrictEqual(nextDepartures(tt, 'b', 620, 2), [635, 665]);
});
