const { add, mul } = require('./app.js');
let bad = 0;
if (add(2, 3) !== 5) { console.error('add(2, 3) gave ' + add(2, 3)); bad++; }
if (mul(2, 3) !== 6) { console.error('mul broke'); bad++; }
if (bad) process.exit(1);
console.log('ok');
