let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const t = require('./timer.js');
need(t.seconds === 90, 'seconds is ' + t.seconds);
need(t.running === false, 'running changed');
if (bad) process.exit(1);
console.log('ok');
