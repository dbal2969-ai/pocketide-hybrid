(function () {
  'use strict';
  const A = window.App;
  let current = 'market-square';
  fetch('data/timetable.json').then(r => r.json()).then(tt => {
    function draw() {
      A.stopList.render(document, tt, current, id => { current = id; draw(); });
      A.board.render(document, tt, current, A.time.serviceNow(new Date()));
    }
    draw();
    setInterval(draw, 30000);
  });
})();
