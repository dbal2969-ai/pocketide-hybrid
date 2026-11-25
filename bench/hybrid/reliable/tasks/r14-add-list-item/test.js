let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { fruits } = require('./menu.js');
need(fruits.join() === 'apple,pear,grape', 'fruits are ' + fruits.join());
if (bad) process.exit(1);
console.log('ok');
