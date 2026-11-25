let count = 0;
document.getElementById('add').addEventListener('click', () => {
  count++;
  document.getElementById('count').textContent = count;
});
