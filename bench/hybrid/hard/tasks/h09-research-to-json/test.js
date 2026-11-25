const fs = require('fs');
let data;
try { data = JSON.parse(fs.readFileSync(__dirname + '/licences.json', 'utf8')); }
catch (e) { console.error('licences.json is missing or not JSON: ' + e.message); process.exit(1); }
const want = { express: 'MIT', lodash: 'MIT', react: 'MIT' };
let bad = 0;
for (const [k, v] of Object.entries(want)) if (data[k] !== v) { console.error(k + ': got ' + JSON.stringify(data[k]) + ', want ' + JSON.stringify(v)); bad++; }
if (bad) process.exit(1);
console.log('ok');
