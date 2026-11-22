(function () {
  'use strict';
  const A = window.App;
  fetch('data/songs.json').then(r => r.json()).then(songs => {
    let queue = A.shuffle.shuffled(songs, null), last = null;
    document.getElementById('next').addEventListener('click', () => {
      if (!queue.length) queue = A.shuffle.shuffled(songs, last);
      last = queue.shift();
      document.getElementById('now').textContent = 'Now playing: ' + last;
    });
  });
})();
