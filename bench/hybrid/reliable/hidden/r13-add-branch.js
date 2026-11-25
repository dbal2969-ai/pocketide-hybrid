let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { describe } = require('./sign.js');
need(describe(3) === 'positive' && describe(0) === 'zero', 'other cases changed');
if (bad) process.exit(1);
console.log('ok');
