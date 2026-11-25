let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { fruits } = require('./menu.js');
need(fruits.length === 3, 'three fruits');
if (bad) process.exit(1);
console.log('ok');
