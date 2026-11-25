let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { label } = require('./user.js');
const got = label({ name: 'Ana', age: 30 });
need(got === 'Ana (30)', 'label gave ' + got);
if (bad) process.exit(1);
console.log('ok');
