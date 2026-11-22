const test = require('node:test');
const assert = require('assert');
const { sessionVolume } = require('../lib/volume.js');
const { monday, parse, iso } = require('../lib/dates.js');

test('session volume is reps times kg over every set', () => {
  assert.strictEqual(sessionVolume({ exercises: [{ sets: [{ reps: 5, kg: 60 }, { reps: 5, kg: 60 }] }, { sets: [{ reps: 10, kg: 20 }] }] }), 800);
});

test('weeks start on Monday', () => {
  assert.strictEqual(iso(monday(parse('2026-03-01'))), '2026-02-23');
  assert.strictEqual(iso(monday(parse('2026-03-02'))), '2026-03-02');
});
