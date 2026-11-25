let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { greet } = require('./hello.js');
need(greet() === 'Hello, friend!', 'greet() gave ' + greet());
if (bad) process.exit(1);
console.log('ok');
