let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { average } = require('./stats.js');
need(average([2, 4]) === 3, 'average gave ' + average([2, 4]));
if (bad) process.exit(1);
console.log('ok');
