# Harbour

A small trading game: sail between harbours, buy cheap, sell dear, keep the crew alive.

Open `index.html` in a browser (it loads the JSON in `data/` with fetch, so serve the folder, e.g. `npx serve`).
Run the tests with `npm test` (Node 18 or later).

## Layout

- `data/` - goods, harbours, offers, ships and sea events, as exported from the design spreadsheet (numbers are text).
- `loader/` - reads the data and converts every field by `schema.js`, once.
- `state/` - the game: crew, cart, market and daily prices, trade, routes, events at sea, travel, ledger, saves.
- `ui/` - the screens. Each has a `rows()` or `lines()` function that returns plain data, and a `render()` that puts it on the page.
- `util/` - money, sorting, layout, geometry, random numbers, labels, converters.
- `main.js` - wires the screens to the state in the browser.

Every module works in Node (`require`) and in the browser (`window.Harbour.<name>`).
