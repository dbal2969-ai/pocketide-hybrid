let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { person, setAge } = require('./person.js');
setAge(30);
need(person.age === 30, 'a good age is no longer set');
if (bad) process.exit(1);
console.log('ok');
