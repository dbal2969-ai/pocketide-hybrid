let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const t = require('./text.js');
need(t.shout('hi') === 'HI!', 'shout');
need(t.whisper('Hi') === 'hi…', 'whisper');
if (bad) process.exit(1);
console.log('ok');
