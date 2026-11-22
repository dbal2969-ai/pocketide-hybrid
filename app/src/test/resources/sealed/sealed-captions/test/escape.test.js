const test = require('node:test');
const assert = require('assert');
const { escapeHtml } = require('../lib/escape.js');
const { toHtml } = require('../lib/exportPage.js');

test('html is escaped', () => {
  assert.strictEqual(escapeHtml('<b>&'), '&lt;b&gt;&amp;');
});

test('the exported page escapes captions', () => {
  assert.ok(toHtml({ title: 'T', photos: [{ file: 'a.jpg', caption: '<script>' }] }).includes('&lt;script&gt;'));
});
