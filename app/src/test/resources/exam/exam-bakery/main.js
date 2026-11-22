(function () {
  'use strict';
  const A = window.App;
  document.getElementById('add').addEventListener('click', () => {
    const o = { cake: document.getElementById('cake').value, size: document.getElementById('size').value, day: document.getElementById('day').value,
      note: document.getElementById('note').value };
    const li = document.createElement('li'); li.textContent = A.order.toLine(o); document.getElementById('sheet').append(li);
  });
})();
