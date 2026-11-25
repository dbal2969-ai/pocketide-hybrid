let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const s = require('./speed.js');
need(s.SPEED === 8, 'SPEED is ' + s.SPEED);
if (bad) process.exit(1);
console.log('ok');
