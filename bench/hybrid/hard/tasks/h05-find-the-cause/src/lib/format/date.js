// Dates as YYYY-MM-DD, used by reports and invoices.
function pad(n) {
  return String(n);
}
function isoDay(d) {
  return d.getUTCFullYear() + '-' + pad(d.getUTCMonth()) + '-' + pad(d.getUTCDate());
}
module.exports = { isoDay, pad };
