// Returns the problems with a password, in this order:
// "too short" (under 8 characters), "no digit", "no uppercase", "has spaces". An empty list means it is fine.
function checkPassword(pw) {
  const out = [];
  if (pw.length < 8) out.push('too short');
  if (!/[0-9]/.test(pw)) out.push('no digit');
  if (!/[A-Z]/.test(pw)) out.push('no uppercase');
  if (/\s/.test(pw)) out.push('has spaces');
  return out;
}
module.exports = { checkPassword };
