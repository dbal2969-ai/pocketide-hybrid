let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { nextLevel } = require('./level.js');
need(nextLevel('3') === 4, 'nextLevel(\'3\') gave ' + JSON.stringify(nextLevel('3')));
if (bad) process.exit(1);
console.log('ok');
