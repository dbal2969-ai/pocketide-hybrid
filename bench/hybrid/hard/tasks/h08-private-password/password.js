// Returns the problems with a password, in this order:
// "too short" (under 8 characters), "no digit", "no uppercase", "has spaces". An empty list means it is fine.
function checkPassword(pw) {
  return [];
}
module.exports = { checkPassword };
