let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { state, tick } = require('./loop.js');
tick(); tick();
need(state.ticks === 2, 'ticks is ' + state.ticks);
need(state.frames === 2, 'frames is ' + state.frames);
if (bad) process.exit(1);
console.log('ok');
