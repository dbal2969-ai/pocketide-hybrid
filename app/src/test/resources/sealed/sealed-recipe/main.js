// Wires the page: loads the data, shows each recipe and the settings.
(function () {
  'use strict';
  const A = window.App;
  let settings = A.settings.load(window.localStorage);

  async function start() {
    const [ingredients, recipes] = await Promise.all(['data/ingredients.json', 'data/recipes.json'].map(p => fetch(p).then(r => r.json())));
    const prices = A.prices.index(ingredients);
    function draw() {
      const list = document.getElementById('recipes');
      list.innerHTML = '';
      for (const r of recipes) {
        const card = document.createElement('article');
        for (const line of A.recipeView.lines(r, prices, settings)) { const p = document.createElement('p'); p.textContent = line; card.append(p); }
        const ul = document.createElement('ul');
        for (const item of A.recipeView.shoppingList(r, prices)) { const li = document.createElement('li'); li.textContent = item; ul.append(li); }
        card.append(ul);
        list.append(card);
      }
      A.settingsView.render(document, settings, s => { settings = s; A.settings.save(window.localStorage, s); draw(); });
    }
    draw();
  }
  start();
})();
