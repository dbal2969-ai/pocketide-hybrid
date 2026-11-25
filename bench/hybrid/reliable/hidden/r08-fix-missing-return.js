let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { average } = require('./stats.js');
need(average([1, 2, 3, 6]) === 3, 'four numbers');
if (bad) process.exit(1);
console.log('ok');
