const { isoDay } = require('./lib/format/date.js');
function dueLine(issued, days) {
  const due = new Date(issued.getTime() + days * 86400000);
  return 'Due ' + isoDay(due);
}
module.exports = { dueLine };
