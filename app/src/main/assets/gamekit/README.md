# Game kit

A playable tile game built from an exported game cache (see the game_cache tool). Written by `game_cache action=scaffold`; open `index.html`.

What is in the game: a 3D world built from the cache (real ground heights, every building and object as its real model, upper floors and
roofs that hide when you walk in), the player and enemies as animated models (stand, walk, run, punch, kick, block, death, chopping with the
axe in hand), a camera that turns and zooms (drag, pinch, mouse wheel), walking with pathfinding, fighting, chopping trees, a bank, the
Stats, Combat, Inventory and Options tabs with the game's own icons, orbs and hit splats (Old School caches), a minimap and compass that turn
with the camera, a chat box, run energy, sounds and a quiet tune, and one save that holds everything. Options switches between the 3D world and
the flat map from above (render.js), which needs no 3D at all. What is not: equipment, prayer, magic, quests, shops, upper floors you can
walk on, music from the cache.

Where things live (change a rule by editing the one file that holds it; the cache data does not change):
- `rules.js`: the player's levels (CONFIG.player), start items, how many enemies and where, how they fight, drops and respawns.
- `gather.js`: trees (level, experience, logs, how fast they grow back, the chance a swing works per axe). Copy it for mining or fishing.
- `skills.js`: the experience table and the skill list (add a skill name to ORDER and SHORT/LABEL).
- `bank.js`, `inv.js`: the bank panel and the 28-slot inventory (tap, long press for Use / Drop / Examine, drag to swap).
- `ui.js`: the side panel tabs and what each shows. `sfx.js`: sounds made in code. `save.js`: versioned saving in local storage.
- `world.js`, `pathfinder.js`: map squares, walls and walking. `render.js`: the flat map and the minimap.
- `view3d.js`: the 3D world, the camera, characters as models, taps to tiles. `anim.js`: plays the cache's animations on models.
- `main.js`: starts everything, handles taps, and picks the animation each character plays (ANIMS at the top). Change START for another place.

Data it expects (`game_cache action=scaffold` writes all of it): `data/floors.json`, `data/objects.json`, `data/maps/m<x>_<z>.json`,
`data/ground`, `data/places` and `data/objects` (pictures for the flat map), `data/scene/s<x>_<z>.bin.gz` (the baked 3D squares),
`data/models/player.json` and `data/models/held_<item>.json`, `data/npcs3d/<npc>.json`, `data/anim/<sequence>.json`, `data/items.json`
and `data/icons`, `data/enemies.json` and `data/npcs`, `data/sprites/<id>.png` (interface pictures, Old School caches), `engine/three.module.min.js`.
With 2009scape's server data in the project, `game_data action=export` and `game_cache action=enemies spawns=...` put the enemies where they
really stand, with real stats, drops and (2009 caches) attack, block and death animations.
Tile coordinates are world tiles: square x * 64 + column, square z * 64 + row; z grows to the north.

Building blocks for other games: `anim.js` (any model with vertex groups), `view3d.js` (any baked tile world, or just characters), `pathfinder.js`,
`inv.js`, `bank.js`, `skills.js`, `sfx.js`, `save.js` and `ui.js` work on their own; `game_assets action=blocks` copies them into any project.
The cache's contents belong to Jagex: keep them for private experiments.
