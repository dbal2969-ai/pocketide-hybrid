let count = 0;
function add() { count++; return count; }
if (typeof module !== 'undefined') module.exports = { add, get: () => count };
