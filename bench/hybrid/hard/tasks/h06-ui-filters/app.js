const list = document.getElementById('list');
const input = document.getElementById('new');
const count = document.getElementById('count');
let items = [];

function render() {
  list.innerHTML = '';
  items.forEach((it, i) => {
    const li = document.createElement('li');
    li.textContent = it.text;
    if (it.done) li.className = 'done';
    li.onclick = () => { items[i].done = !items[i].done; render(); };
    list.appendChild(li);
  });
  count.textContent = items.filter(it => !it.done).length + ' left';
}

document.getElementById('add').onclick = () => {
  const t = input.value.trim();
  if (!t) return;
  items.push({ text: t, done: false });
  input.value = '';
  render();
};

render();
