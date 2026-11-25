// Keyboard controls: each key calls move() with a direction.
document.addEventListener('keydown', e => {
  if (e.key === 'ArrowLeft') move('left');
  if (e.key === 'ArrowRight') move('right');
});
