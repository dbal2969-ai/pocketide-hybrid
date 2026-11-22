# Recipe scaler

Reads a recipe's ingredient lines for scaling. `readIngredients(text)` in `lib/readIngredients.js` gives `{ qty, unit, name }` per line (qty a number; unit as written, lower case, or null for a count). Open `index.html`; tests: `npm test`.
