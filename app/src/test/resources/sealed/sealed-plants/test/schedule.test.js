const test = require('node:test');
const assert = require('assert');
const { nextWatering, thisWeek } = require('../lib/schedule.js');

test('next watering is the last plus the interval', () => {
  assert.strictEqual(nextWatering({ group: 'fern', lastWatered: '2026-03-03' }, { fern: 3 }), '2026-03-06');
});

test('the week list is soonest first', () => {
  const plants = [{ id: 'a', name: 'A', group: 'x', lastWatered: '2026-03-01' }, { id: 'b', name: 'B', group: 'y', lastWatered: '2026-03-01' }];
  assert.deepStrictEqual(thisWeek(plants, { x: 5, y: 2 }, '2026-03-02').map(r => r.plant.id), ['b', 'a']);
});
