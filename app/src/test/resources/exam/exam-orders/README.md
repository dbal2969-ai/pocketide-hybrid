# Orders

Imports the orders file the shop's till exports, for the day's baking list. `readOrders(text)` in `lib/readOrders.js` gives `{ order, customer, item, qty, price, note }` per line (order and qty whole numbers, price a number, note text, "" when none). Open `index.html`; tests: `npm test`.
