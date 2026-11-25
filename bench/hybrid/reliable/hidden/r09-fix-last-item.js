let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { first, last } = require('./list.js');
need(last(['x']) === 'x', 'one item');
need(first([7, 8]) === 7, 'first changed');
if (bad) process.exit(1);
console.log('ok');
