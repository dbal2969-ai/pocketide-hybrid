let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
let t = {};
try { t = require('./temp.js'); } catch (e) { need(false, 'temp.js is missing'); }
need(t.toF && t.toF(100) === 212, 'toF(100) gave ' + (t.toF && t.toF(100)));
if (bad) process.exit(1);
console.log('ok');
