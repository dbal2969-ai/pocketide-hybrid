let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { last } = require('./list.js');
need(last([1, 2, 3]) === 3, 'last gave ' + last([1, 2, 3]));
if (bad) process.exit(1);
console.log('ok');
