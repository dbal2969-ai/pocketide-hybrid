let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { person, setAge } = require('./person.js');
let threw = false;
try { setAge(-1); } catch (e) { threw = e instanceof Error; }
need(threw, 'setAge(-1) did not throw');
if (bad) process.exit(1);
console.log('ok');
