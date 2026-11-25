let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const s = require('./speed.js');
need(s.TURBO === 2, 'TURBO changed');
if (bad) process.exit(1);
console.log('ok');
