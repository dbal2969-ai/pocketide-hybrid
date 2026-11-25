let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const h = require('./helpers.js');
for (let i = 1; i <= 45; i++) need(h['helper' + i](0) === i, 'helper' + i + ' changed');
if (bad) process.exit(1);
console.log('ok');
