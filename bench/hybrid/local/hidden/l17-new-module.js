// Hidden check for l17-new-module: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { greet } = require('./greet.js');
need(greet('Bo') === 'Hello, Bo!', 'greet(Bo) gave ' + greet('Bo'));
if (bad) process.exit(1);
console.log('ok');
