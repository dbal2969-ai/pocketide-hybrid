let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { largest } = require('./max.js');
need(largest([9, 1, 2]) === 9, 'first');
need(largest([3, 8, 1]) === 8, 'middle');
need(largest([4]) === 4, 'one item');
if (bad) process.exit(1);
console.log('ok');
