let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { repeat } = require('./repeat.js');
need(repeat('x', 3) === 'xxx', 'old calls changed');
if (bad) process.exit(1);
console.log('ok');
