let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { add } = require('./app.js');
need(add(2, 3) === 5, 'add');
if (bad) process.exit(1);
console.log('ok');
