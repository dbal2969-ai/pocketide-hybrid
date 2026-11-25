// Dates as YYYY-MM-DD, used by reports and invoices.
function pad(n) {
  return String(n).padStart(2, '0');
}
function isoDay(d) {
  return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
}
module.exports = { isoDay, pad };
