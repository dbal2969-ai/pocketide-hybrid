// A stand-in for a network call: id 'bad' fails; later ids answer sooner.
function fetchItem(id, index) {
  return new Promise((resolve, reject) => setTimeout(() => {
    if (id === 'bad') reject(new Error('not found: ' + id));
    else resolve({ id, name: 'Item ' + id });
  }, 40 - index * 10));
}
module.exports = { fetchItem };
