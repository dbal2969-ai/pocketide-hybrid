let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const c = require('./config.js');
need(c.speed === 150, 'speed is ' + c.speed);
need(c.lives === 3 && c.level === 1, 'other fields changed');
if (bad) process.exit(1);
console.log('ok');
