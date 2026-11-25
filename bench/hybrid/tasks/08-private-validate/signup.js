function signup(form) {
  return { ok: true, email: form.email, name: form.name };
}
module.exports = { signup };
