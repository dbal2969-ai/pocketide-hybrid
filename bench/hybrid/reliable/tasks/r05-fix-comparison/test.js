let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { largest } = require('./max.js');
need(largest([1, 5, 9]) === 9, 'largest([1,5,9]) gave ' + largest([1, 5, 9]));
if (bad) process.exit(1);
console.log('ok');
