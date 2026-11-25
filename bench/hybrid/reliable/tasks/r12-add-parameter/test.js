let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { repeat } = require('./repeat.js');
need(repeat('ab', 3, '-') === 'ab-ab-ab', 'gave ' + repeat('ab', 3, '-'));
if (bad) process.exit(1);
console.log('ok');
