// Hidden check for l08-fix-property: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { label } = require('./user.js');
need(label({ name: 'Bo', age: 5 }) === 'Bo (5)', 'label gave ' + label({ name: 'Bo', age: 5 }));
if (bad) process.exit(1);
console.log('ok');
