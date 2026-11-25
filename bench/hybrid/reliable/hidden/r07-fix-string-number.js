let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { nextLevel } = require('./level.js');
need(nextLevel('9') === 10, '9');
need(nextLevel(2) === 3, 'a number still works');
if (bad) process.exit(1);
console.log('ok');
