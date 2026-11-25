let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { describe } = require('./sign.js');
need(describe(-2) === 'negative', 'describe(-2) gave ' + describe(-2));
if (bad) process.exit(1);
console.log('ok');
