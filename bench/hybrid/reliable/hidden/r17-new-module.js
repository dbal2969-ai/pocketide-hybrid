let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { toF } = require('./temp.js');
need(toF(0) === 32, 'toF(0)');
need(toF(-40) === -40, 'toF(-40)');
if (bad) process.exit(1);
console.log('ok');
