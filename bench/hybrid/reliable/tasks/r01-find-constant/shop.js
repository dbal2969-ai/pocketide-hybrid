const { START_GOLD } = require('./economy.js');
let gold = START_GOLD;
function buy(cost) { if (cost <= gold) gold -= cost; return gold; }
module.exports = { buy };
