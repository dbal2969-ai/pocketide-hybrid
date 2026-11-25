let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const fs = require('fs');
const html = fs.readFileSync(__dirname + '/index.html', 'utf8');
need(/id="help"/.test(html) && !/id="help"[^>]*big/.test(html), 'the Help button changed');
need(/font-family:\s*sans-serif/.test(fs.readFileSync(__dirname + '/style.css', 'utf8')), 'the body rule changed');
if (bad) process.exit(1);
console.log('ok');
