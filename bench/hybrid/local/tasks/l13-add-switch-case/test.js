let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { delta } = require('./dir.js');
need(delta('left').join() === '-1,0', 'delta(left) gave ' + delta('left'));
need(delta('up').join() === '0,-1', 'up changed');
if (bad) process.exit(1);
console.log('ok');
