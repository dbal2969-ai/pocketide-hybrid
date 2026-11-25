// Hidden check for l15-keep-existing: inputs the model never saw (run by analyze.py, not shown in the app).
let bad = 0;
function need(c, msg) { if (!c) { console.error('hidden check: ' + msg); bad++; } }
const { state, tick } = require('./loop.js');
tick(); tick(); tick();
need(state.ticks === 3 && state.frames === 3, 'after 3 ticks: ' + JSON.stringify(state));
if (bad) process.exit(1);
console.log('ok');
