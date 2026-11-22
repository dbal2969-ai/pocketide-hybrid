// The side panel: a row of tabs and what each one shows. Combat, Stats, Inv and Options work; the other tabs of the real game
// are drawn dimmed because this game does not have them yet. Written for PocketIDE's game kit; MIT licensed.
import { ORDER, LABEL, SHORT } from './skills.js';

const TABS = [['combat', 'Combat', 'Com'], ['stats', 'Stats', 'Sta'], ['quests', 'Quests', 'Que'], ['inv', 'Inventory', 'Inv'], ['equip', 'Equipment', 'Eqp'], ['prayer', 'Prayer', 'Pra'], ['magic', 'Magic', 'Mag'],
  ['clan', 'Clan', 'Cla'], ['friends', 'Friends', 'Fri'], ['account', 'Account', 'Acc'], ['logout', 'Logout', 'Out'], ['options', 'Options', 'Opt'], ['emotes', 'Emotes', 'Emo'], ['music', 'Music', 'Mus']];
const WORKS = new Set(['combat', 'stats', 'inv', 'options']);   // 'quests' too when the game has quests (content.js)
// the game's own pictures (sprites) for the tabs and skills, exported from an Old School cache into data/sprites; text when missing
export const TAB_SPRITE = { combat: 168, stats: 898, quests: 776, inv: 900, equip: 901, prayer: 902, magic: 903, clan: 895, friends: 905, logout: 907, options: 908, emotes: 909, music: 910 };
export const SKILL_SPRITE = { attack: 197, strength: 198, defence: 199, ranged: 200, prayer: 201, magic: 202, hitpoints: 203, agility: 204, herblore: 205, thieving: 206,
  crafting: 207, fletching: 208, mining: 209, smithing: 210, fishing: 211, cooking: 212, firemaking: 213, woodcutting: 214, runecrafting: 215, slayer: 216, farming: 217, hunter: 220, construction: 221 };
const sprite = (id, cls, fallback) => {
  const im = document.createElement('img'); im.className = cls; im.src = 'data/sprites/' + id + '.png'; im.alt = fallback; im.draggable = false;
  im.onerror = () => { const t = document.createElement('span'); t.textContent = fallback; im.replaceWith(t); };
  return im;
};
const STYLES = [['accurate', 'Punch', 'Accurate', 'Attack'], ['aggressive', 'Kick', 'Aggressive', 'Strength'], ['defensive', 'Block', 'Defensive', 'Defence']];

const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

// o: { skills, game, say, sfx, sprites (true when data/sprites holds the game's pictures), view3d ({ on, set(on) } when the 3D world exists), save: { now(), at() (time of the last save), reset() }, root: the element that holds the tabs and the views }
export function buildPanel(o) {
  const tabsEl = document.getElementById('tabs'), box = document.getElementById('panel');
  const views = { inv: document.getElementById('inv') };
  if (o.content && o.content.questList.length) WORKS.add('quests');
  for (const k of ['stats', 'combat', 'options', 'quests']) { if (!WORKS.has(k)) continue; views[k] = el('div', 'view'); views[k].id = k + 'v'; views[k].hidden = true; box.appendChild(views[k]); }
  let current = 'inv', reading = null;   // reading: the quest whose journal is open

  function show(name) {
    if (!WORKS.has(name)) return;
    current = name;
    for (const [k, v] of Object.entries(views)) v.hidden = k !== name;
    for (const b of tabsEl.children) b.classList.toggle('on', b.dataset.tab === name);
    refresh();
    o.sfx && o.sfx.play('click');
  }

  tabsEl.innerHTML = '';
  for (const [key, title, label] of TABS) {
    const b = el('div', 'tab' + (WORKS.has(key) ? '' : ' off')); b.dataset.tab = key;
    if (o.sprites && TAB_SPRITE[key]) b.appendChild(sprite(TAB_SPRITE[key], 'ticon', label)); else b.textContent = label; b.title = WORKS.has(key) ? title : title + ' (not in this game yet)';
    if (WORKS.has(key)) b.addEventListener('pointerdown', e => { e.stopPropagation(); show(key); });
    tabsEl.appendChild(b);
  }

  function refresh() {
    if (current === 'stats') drawStats();
    else if (current === 'combat') drawCombat();
    else if (current === 'options') drawOptions();
    else if (current === 'quests') drawQuests();
  }

  // the quest list (red: not started, yellow: started, green: done) and, after a tap, that quest's journal
  function drawQuests() {
    const v = views.quests, c = o.content; v.innerHTML = '';
    if (reading) {
      const back = el('div', 'obtn', '‹ Quest list'); back.id = 'qback';
      back.addEventListener('pointerdown', e => { e.stopPropagation(); reading = null; drawQuests(); });
      v.appendChild(back);
      v.appendChild(el('div', 'qtitle', c.questName(reading)));
      for (const l of c.journal(reading)) v.appendChild(el('div', 'qline' + (l.done ? ' qdone' : ''), l.text));
      return;
    }
    v.appendChild(el('div', 'sfoot', 'Quest Points: ' + c.points()));
    for (const q of c.questList) {
      const st = c.status(q.id), b = el('div', 'quest ' + (st === 'done' ? 'qgreen' : st === 'started' ? 'qyellow' : 'qred'), q.name || q.id); b.dataset.quest = q.id;
      b.addEventListener('pointerdown', e => { e.stopPropagation(); reading = q.id; drawQuests(); o.sfx && o.sfx.play('click'); });
      v.appendChild(b);
    }
  }

  function drawStats() {
    const v = views.stats; v.innerHTML = '';
    const grid = el('div', 'sgrid');
    for (const s of ORDER) {
      const c = el('div', 'scell'); c.dataset.skill = s;
      if (o.sprites && SKILL_SPRITE[s]) c.appendChild(sprite(SKILL_SPRITE[s], 'sicon', SHORT[s])); else c.appendChild(el('span', 'sname', SHORT[s]));
      c.appendChild(el('b', 'slvl', String(o.skills.level(s)) + '/' + String(o.skills.level(s))));
      c.addEventListener('pointerdown', e => { e.stopPropagation(); o.say(LABEL[s] + ': ' + Math.floor(o.skills.xp[s] || 0).toLocaleString() + ' xp' + (o.skills.toNext(s) ? ', ' + o.skills.toNext(s).toLocaleString() + ' to the next level.' : '.')); });
      grid.appendChild(c);
    }
    v.appendChild(grid);
    v.appendChild(el('div', 'sfoot', 'Total level: ' + o.skills.total()));
    v.appendChild(el('div', 'sfoot', 'Combat level: ' + o.game.combat));
  }

  function drawCombat() {
    const v = views.combat; v.innerHTML = '';
    v.appendChild(el('div', 'sfoot', 'Combat level: ' + o.game.combat));
    v.appendChild(el('div', 'sfoot', 'Hitpoints: ' + o.game.player.hp + ' / ' + o.game.player.maxHp));
    for (const [key, name, style, skill] of STYLES) {
      const b = el('div', 'obtn' + (o.game.player.style === key ? ' on' : ''), name + ' · ' + style); b.dataset.style = key; b.title = 'Trains ' + skill;
      b.appendChild(el('small', null, 'trains ' + skill));
      b.addEventListener('pointerdown', e => { e.stopPropagation(); o.game.player.style = key; o.say('You will now train ' + skill + ' with each hit.'); drawCombat(); o.sfx && o.sfx.play('click'); });
      v.appendChild(b);
    }
  }

  function drawOptions() {
    const v = views.options; v.innerHTML = '';
    const vol = el('input'); vol.type = 'range'; vol.min = 0; vol.max = 100; vol.value = Math.round(o.sfx.volume * 100); vol.id = 'volume';
    vol.addEventListener('input', () => o.sfx.setVolume(vol.value / 100));
    vol.addEventListener('pointerdown', e => e.stopPropagation());
    v.appendChild(el('div', 'sfoot', 'Volume')); v.appendChild(vol);
    const mute = el('div', 'obtn' + (o.sfx.muted ? ' on' : ''), o.sfx.muted ? 'Sound is off (tap to turn on)' : 'Sound is on (tap to mute)'); mute.id = 'mute';
    mute.addEventListener('pointerdown', e => { e.stopPropagation(); o.sfx.setMuted(!o.sfx.muted); drawOptions(); });
    v.appendChild(mute);
    if (o.view3d) {   // 3D or the flat map from above (the game saves first, then reloads in the other view)
      const vb = el('div', 'obtn' + (o.view3d.on ? ' on' : ''), o.view3d.on ? '3D view (tap for the flat map)' : 'Flat map (tap for 3D)'); vb.id = 'view3d';
      vb.addEventListener('pointerdown', e => { e.stopPropagation(); o.save.now(); o.view3d.set(!o.view3d.on); });
      v.appendChild(vb);
    }
    const sv = el('div', 'obtn', 'Save now'); sv.id = 'savenow';
    sv.addEventListener('pointerdown', e => { e.stopPropagation(); o.save.now(); o.say('Game saved.'); drawOptions(); });
    v.appendChild(sv);
    const at = o.save.at();
    v.appendChild(el('div', 'sfoot', at ? 'Saved ' + new Date(at).toLocaleTimeString() : 'Not saved yet'));
    const rs = el('div', 'obtn danger', 'Reset game'); rs.id = 'reset';
    let armed = 0;
    rs.addEventListener('pointerdown', e => {
      e.stopPropagation();
      if (!armed) { armed = setTimeout(() => { armed = 0; rs.textContent = 'Reset game'; rs.classList.remove('on'); }, 4000); rs.textContent = 'Really erase everything? Tap again'; rs.classList.add('on'); return; }
      clearTimeout(armed); o.save.reset();
    });
    v.appendChild(rs);
  }

  show('inv');
  return { show, refresh, get current() { return current; } };
}
