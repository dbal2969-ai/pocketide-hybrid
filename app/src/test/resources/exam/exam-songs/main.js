(function () {
  'use strict';
  const A = window.App;
  document.getElementById('read').addEventListener('click', () => {
    const rows = A.readSongs.readSongs(document.getElementById('paste').value);
    document.getElementById('out').textContent = JSON.stringify(rows, null, 2);
  });
})();
