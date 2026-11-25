const app = require('./app.js');
if (app.pick(1) !== 1 || app.pick(2) !== 2 || app.unpick(1) !== 1) { console.error('pick/unpick broke'); process.exit(1); }
if (typeof app.setDay !== 'function') { console.error('setDay is missing'); process.exit(1); }
app.setDay(3, 2);
if (app.dayOf(3) !== 2) { console.error('setDay/dayOf wrong'); process.exit(1); }
console.log('ok');
