let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { greet } = require('./hello.js');
need(greet('Bo') === 'Hello, Bo!', 'a name still works');
if (bad) process.exit(1);
console.log('ok');
