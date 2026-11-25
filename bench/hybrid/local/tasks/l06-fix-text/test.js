let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { greet } = require('./greet.js');
need(greet('Ana') === 'Hello, Ana!', 'greet gave ' + greet('Ana'));
if (bad) process.exit(1);
console.log('ok');
