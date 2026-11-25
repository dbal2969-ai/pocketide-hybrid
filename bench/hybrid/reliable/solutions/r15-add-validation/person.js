const person = { age: 0 };
function setAge(a) {
  if (a < 0) throw new Error('age cannot be negative');
  person.age = a;
}
module.exports = { person, setAge };
