let bad = 0;
function need(c, msg) { if (!c) { console.error(msg); bad++; } }
const { player } = require('./player.js');
need(player.hp === 10, 'player starts with ' + player.hp);
if (bad) process.exit(1);
console.log('ok');
