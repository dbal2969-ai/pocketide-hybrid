let count = 0;
function add() { count++; return count; }
function reset() { count = 0; }
if (typeof module !== 'undefined') module.exports = { add, reset, get: () => count };
