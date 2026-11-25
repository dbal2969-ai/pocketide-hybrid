let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const h = require('./helpers.js');
need(h.helper40(1) === 41, 'helper40(1) gave ' + h.helper40(1));
if (bad) process.exit(1);
console.log('ok');
