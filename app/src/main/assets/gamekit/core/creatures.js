// Low-poly creatures made in code, with their own animations: no model files needed. Good for any 3D game.
//   const c = makeCreature(THREE, { body: 'humanoid', size: 2.4, color: '#6b7f4a', bulk: 1.4, weapon: 'club', horns: false });
//   scene.add(c.group); every frame: c.update(dt, speed)  (speed in metres per second: 0 stands, walking swings the limbs)
//   c.attack() swings, c.hurt() flashes, c.die() falls over; c.height is its height in metres.
// Bodies: humanoid (people, bandits, trolls, vampires, imps, skeletons), beast (wolves, bears, boars, deer), dragon (four legs, wings,
// long neck and tail), slime (a bouncing blob), flyer (bats, birds: wings and a small body, floats in the air).
// Options: size (height in metres), color (body), skin (head and hands), accent (clothes, wings, horns), bulk (0.6 thin .. 1.6 broad),
// horns, wings, tail, cape, hood, ears (pointed), weapon (sword, axe, club, staff, dagger, bow, none), eyes (colour), legs (beast legs, 4).
// Part of PocketIDE's game kit; MIT licensed.

const mats = new Map();
function mat(THREE, color, own) {
  if (own) return new THREE.MeshLambertMaterial({ color, flatShading: true });
  if (!mats.has(color)) mats.set(color, new THREE.MeshLambertMaterial({ color, flatShading: true }));
  return mats.get(color);
}

export function makeCreature(THREE, spec = {}) {
  const s = { body: 'humanoid', size: 1.8, color: '#8a6a4a', skin: '#e0b090', accent: '#5a3a2a', bulk: 1, weapon: 'none', eyes: '#111111', ...spec };
  const H = s.size, g = new THREE.Group(), root = new THREE.Group(); g.add(root);
  const own = [];   // this creature's own materials, so a hit flashes only this one
  const m = c => { const x = mat(THREE, c, true); own.push(x); return x; };
  const body = m(s.color), skin = m(s.skin), accent = m(s.accent), eye = mat(THREE, s.eyes);
  const box = (w, h, d, material, x = 0, y = 0, z = 0, parent = root) => { const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), material); b.position.set(x, y, z); parent.add(b); return b; };
  const pivot = (x, y, z, parent = root) => { const p = new THREE.Group(); p.position.set(x, y, z); parent.add(p); return p; };
  const cone = (r, h, material, x, y, z, parent = root, seg = 5) => { const c = new THREE.Mesh(new THREE.ConeGeometry(r, h, seg), material); c.position.set(x, y, z); parent.add(c); return c; };
  const parts = {}; let kind = s.body;

  if (kind === 'humanoid') {
    const legLen = H * 0.46, torsoH = H * 0.32, headS = H * 0.15, w = H * 0.26 * s.bulk, d = H * 0.15 * s.bulk;
    parts.hipL = pivot(-w * 0.25, legLen, 0); parts.hipR = pivot(w * 0.25, legLen, 0);
    for (const hp of [parts.hipL, parts.hipR]) { box(w * 0.38, legLen, d * 0.8, accent, 0, -legLen / 2, 0, hp); box(w * 0.42, legLen * 0.12, d * 1.1, body, 0, -legLen + legLen * 0.06, d * 0.1, hp); }
    parts.torso = box(w, torsoH, d, body, 0, legLen + torsoH / 2, 0);
    const sh = legLen + torsoH * 0.95, armLen = H * 0.38;
    parts.armL = pivot(-w / 2 - w * 0.13, sh, 0); parts.armR = pivot(w / 2 + w * 0.13, sh, 0);
    for (const ap of [parts.armL, parts.armR]) { box(w * 0.24, armLen, d * 0.7, body, 0, -armLen / 2, 0, ap); box(w * 0.26, armLen * 0.18, d * 0.75, skin, 0, -armLen + armLen * 0.09, 0, ap); }
    parts.head = pivot(0, legLen + torsoH, 0);
    box(headS, headS, headS, skin, 0, headS / 2 + H * 0.01, 0, parts.head);
    box(headS * 0.18, headS * 0.12, 0.02, eye, -headS * 0.22, headS * 0.62, headS / 2 + 0.01, parts.head); box(headS * 0.18, headS * 0.12, 0.02, eye, headS * 0.22, headS * 0.62, headS / 2 + 0.01, parts.head);
    if (s.hood) { box(headS * 1.2, headS * 1.15, headS * 1.2, accent, 0, headS * 0.6, -headS * 0.08, parts.head); }
    if (s.horns) { cone(headS * 0.12, headS * 0.6, accent, -headS * 0.35, headS * 1.25, 0, parts.head).rotation.z = 0.4; cone(headS * 0.12, headS * 0.6, accent, headS * 0.35, headS * 1.25, 0, parts.head).rotation.z = -0.4; }
    if (s.ears) { cone(headS * 0.1, headS * 0.5, skin, -headS * 0.6, headS * 0.8, 0, parts.head).rotation.z = 1.1; cone(headS * 0.1, headS * 0.5, skin, headS * 0.6, headS * 0.8, 0, parts.head).rotation.z = -1.1; }
    if (s.cape) { parts.cape = pivot(0, sh, -d / 2 - 0.02); box(w * 1.05, torsoH + legLen * 0.75, 0.04, accent, 0, -(torsoH + legLen * 0.75) / 2, 0, parts.cape); }
    if (s.wings) { parts.wingL = pivot(-w * 0.2, sh - torsoH * 0.2, -d / 2); parts.wingR = pivot(w * 0.2, sh - torsoH * 0.2, -d / 2);
      for (const [wp, sg] of [[parts.wingL, -1], [parts.wingR, 1]]) { const wg = box(H * 0.45, H * 0.3, 0.03, accent, sg * H * 0.22, 0, -0.02, wp); wg.rotation.z = sg * 0.3; } }
    if (s.tail) { parts.tail = pivot(0, legLen * 0.95, -d / 2); box(0.06 * H, 0.06 * H, H * 0.4, accent, 0, 0, -H * 0.2, parts.tail).rotation.x = 0.5; }
    const hand = pivot(0, -armLen * 0.95, d * 0.2, parts.armR); parts.hand = hand;
    const wl = H * 0.5, metal = mat(THREE, '#c9ccd2'), wood = mat(THREE, '#6b4a2a');
    if (s.weapon === 'sword') { box(0.05, wl, 0.02, metal, 0, 0, wl / 2 * 0.9, hand).rotation.x = Math.PI / 2; box(0.18, 0.04, 0.05, wood, 0, 0, 0.02, hand); }
    if (s.weapon === 'dagger') box(0.04, wl * 0.45, 0.02, metal, 0, 0, wl * 0.22, hand).rotation.x = Math.PI / 2;
    if (s.weapon === 'axe') { box(0.05, wl, 0.05, wood, 0, 0, wl / 2 * 0.9, hand).rotation.x = Math.PI / 2; box(0.03, 0.22, 0.18, metal, 0, 0.08, wl * 0.85, hand); }
    if (s.weapon === 'club') { const cl = box(0.12 * s.bulk, wl * 1.1, 0.12 * s.bulk, wood, 0, 0, wl * 0.5, hand); cl.rotation.x = Math.PI / 2; }
    if (s.weapon === 'staff') { box(0.05, H * 0.9, 0.05, wood, 0, H * 0.2, 0.05, hand); const orb = new THREE.Mesh(new THREE.IcosahedronGeometry(0.09), mat(THREE, s.accent)); orb.position.set(0, H * 0.66, 0.05); hand.add(orb); }
    if (s.weapon === 'bow') { const bw = box(0.04, H * 0.55, 0.04, wood, 0, 0.05, 0.08, parts.armL); bw.position.y = -armLen * 0.9; }
    parts.legLen = legLen;
  } else if (kind === 'beast' || kind === 'dragon') {
    const legLen = H * 0.48, len = H * (kind === 'dragon' ? 1.8 : 1.3) * s.bulk, bh = H * 0.45, bw = H * 0.42 * s.bulk;
    parts.torso = box(bw, bh, len, body, 0, legLen + bh / 2, 0);
    const legs = []; const n = Math.max(2, s.legs || 4);
    for (let i = 0; i < n; i++) { const front = i < n / 2, side = i % 2 ? 1 : -1; const p = pivot(side * bw * 0.36, legLen + bh * 0.1, (front ? 1 : -1) * len * 0.36); box(bw * 0.24, legLen, bw * 0.24, body, 0, -legLen / 2, 0, p); box(bw * 0.28, legLen * 0.12, bw * 0.32, accent, 0, -legLen + legLen * 0.06, bw * 0.04, p); legs.push(p); }
    parts.legs = legs;
    const neckLen = kind === 'dragon' ? H * 0.75 : H * 0.25, headS = H * (kind === 'dragon' ? 0.32 : 0.3);
    parts.neck = pivot(0, legLen + bh * 0.8, len / 2);
    if (kind === 'dragon') { const nk = box(bw * 0.35, bw * 0.35, neckLen, body, 0, neckLen * 0.35, neckLen / 2, parts.neck); nk.rotation.x = -0.7; }
    parts.head = pivot(0, kind === 'dragon' ? neckLen * 0.75 : 0, kind === 'dragon' ? neckLen * 0.7 : headS * 0.2, parts.neck);
    box(headS * 0.8, headS * 0.7, headS * 1.2, body, 0, 0, headS * 0.5, parts.head);
    box(headS * 0.14, headS * 0.12, 0.02, eye, -headS * 0.25, headS * 0.18, headS * 1.11, parts.head); box(headS * 0.14, headS * 0.12, 0.02, eye, headS * 0.25, headS * 0.18, headS * 1.11, parts.head);
    if (s.ears !== false && kind === 'beast') { cone(headS * 0.13, headS * 0.35, body, -headS * 0.28, headS * 0.45, headS * 0.2, parts.head); cone(headS * 0.13, headS * 0.35, body, headS * 0.28, headS * 0.45, headS * 0.2, parts.head); }
    if (s.horns || kind === 'dragon') { cone(headS * 0.1, headS * 0.6, accent, -headS * 0.25, headS * 0.5, -headS * 0.05, parts.head).rotation.x = -0.6; cone(headS * 0.1, headS * 0.6, accent, headS * 0.25, headS * 0.5, -headS * 0.05, parts.head).rotation.x = -0.6; }
    if (s.tail !== false) { parts.tail = pivot(0, legLen + bh * 0.7, -len / 2); const tl = kind === 'dragon' ? H * 1.1 : H * 0.35; box(bw * (kind === 'dragon' ? 0.3 : 0.15), bw * (kind === 'dragon' ? 0.3 : 0.15), tl, kind === 'dragon' ? body : accent, 0, -tl * 0.15, -tl / 2, parts.tail).rotation.x = 0.3; }
    if (s.wings || kind === 'dragon') { parts.wingL = pivot(-bw / 2, legLen + bh, len * 0.15); parts.wingR = pivot(bw / 2, legLen + bh, len * 0.15);
      for (const [wp, sg] of [[parts.wingL, -1], [parts.wingR, 1]]) { const shape = new THREE.Shape(); shape.moveTo(0, 0); shape.lineTo(sg * H * 1.3, H * 0.25); shape.lineTo(sg * H * 1.1, -H * 0.45); shape.lineTo(sg * H * 0.35, -H * 0.55); shape.lineTo(0, 0);
        const wm = new THREE.Mesh(new THREE.ShapeGeometry(shape), m(s.accent)); wm.material.side = THREE.DoubleSide; wm.rotation.x = -Math.PI / 2; wp.add(wm); } }
    parts.legLen = legLen;
  } else if (kind === 'slime') {
    parts.blob = new THREE.Mesh(new THREE.SphereGeometry(H / 2, 10, 8), body); parts.blob.position.y = H / 2; root.add(parts.blob);
    box(H * 0.1, H * 0.12, 0.02, eye, -H * 0.14, H * 0.6, H * 0.46); box(H * 0.1, H * 0.12, 0.02, eye, H * 0.14, H * 0.6, H * 0.46);
  } else {   // flyer
    kind = 'flyer';
    parts.torso = box(H * 0.3, H * 0.3, H * 0.45, body, 0, 0, 0); root.position.y = H * 1.2;
    box(H * 0.06, H * 0.06, 0.02, eye, -H * 0.07, H * 0.06, H * 0.23); box(H * 0.06, H * 0.06, 0.02, eye, H * 0.07, H * 0.06, H * 0.23);
    parts.wingL = pivot(-H * 0.15, 0.05, 0); parts.wingR = pivot(H * 0.15, 0.05, 0);
    for (const [wp, sg] of [[parts.wingL, -1], [parts.wingR, 1]]) box(H * 0.7, 0.03, H * 0.35, accent, sg * H * 0.35, 0, 0, wp);
  }

  // the box under the feet: a soft shadow disc
  const shadow = new THREE.Mesh(new THREE.CircleGeometry(Math.max(0.3, H * (kind === 'humanoid' ? 0.22 : 0.4) * s.bulk), 12), new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.25, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.y = 0.03; g.add(shadow);

  let phase = 0, attackT = 0, hurtT = 0, deadT = -1, t = 0;
  const c = {
    group: g, parts, spec: s, kind, height: kind === 'flyer' ? H * 1.5 : H, dead: false,
    attack() { if (deadT < 0) attackT = 0.45; },
    hurt() { if (deadT < 0) hurtT = 0.18; },
    die() { if (deadT < 0) { deadT = 0; c.dead = true; } },
    revive() { deadT = -1; c.dead = false; root.rotation.set(0, 0, 0); root.position.y = kind === 'flyer' ? H * 1.2 : 0; g.visible = true; shadow.visible = true; },
    get attacking() { return attackT > 0; },
    update(dt, speed = 0) {
      t += dt;
      if (deadT >= 0) {   // fall over, then sink
        deadT += dt; const k = Math.min(1, deadT / 0.6);
        root.rotation.z = k * Math.PI / 2 * (kind === 'humanoid' ? 1 : 0.6); root.position.y = (kind === 'flyer' ? H * 1.2 * (1 - k) : 0) - Math.max(0, deadT - 1.5) * 0.6;
        shadow.visible = deadT < 1.5; if (deadT > 3) g.visible = false; return;
      }
      const moving = speed > 0.05;
      phase += dt * (moving ? 2.2 + speed * 1.4 : 0); const sw = Math.sin(phase), amp = moving ? Math.min(0.9, 0.35 + speed * 0.12) : 0;
      const breathe = Math.sin(t * 2) * 0.02;
      if (kind === 'humanoid') {
        parts.hipL.rotation.x = sw * amp; parts.hipR.rotation.x = -sw * amp;
        parts.armL.rotation.x = -sw * amp * 0.8; parts.armR.rotation.x = sw * amp * 0.8;
        root.position.y = moving ? Math.abs(Math.cos(phase)) * 0.05 * H : breathe * H * 0.3;
        if (parts.cape) parts.cape.rotation.x = 0.1 + (moving ? 0.25 + Math.sin(phase * 2) * 0.05 : 0.02);
        if (parts.tail) parts.tail.rotation.y = Math.sin(t * 3) * 0.3;
      } else if (kind === 'beast' || kind === 'dragon') {
        parts.legs.forEach((l, i) => { l.rotation.x = (i % 2 === (i < parts.legs.length / 2 ? 0 : 1) ? sw : -sw) * amp * 0.7; });
        root.position.y = moving ? Math.abs(Math.cos(phase)) * 0.04 * H : 0;
        if (parts.tail) parts.tail.rotation.y = Math.sin(t * (moving ? 6 : 2)) * 0.25;
        if (parts.neck) parts.neck.rotation.x = Math.sin(t * 1.5) * 0.05;
      } else if (kind === 'slime') {
        const b = Math.abs(Math.sin(t * (moving ? 7 : 3))); parts.blob.scale.set(1 + (1 - b) * 0.15, 0.8 + b * 0.25, 1 + (1 - b) * 0.15); root.position.y = moving ? b * 0.3 * H : 0;
      }
      if (parts.wingL) { const f = kind === 'flyer' ? Math.sin(t * 14) * 0.7 : (kind === 'dragon' ? Math.sin(t * (moving ? 4 : 1.5)) * (moving ? 0.6 : 0.15) : Math.sin(t * 3) * 0.15); parts.wingL.rotation.z = f; parts.wingR.rotation.z = -f; }
      if (kind === 'flyer') root.position.y = H * 1.2 + Math.sin(t * 2.5) * 0.15 * H;
      if (attackT > 0) {   // a swing (or a bite / lunge) over 0.45 s
        attackT = Math.max(0, attackT - dt); const k = 1 - attackT / 0.45, swing = Math.sin(k * Math.PI);
        if (kind === 'humanoid') { parts.armR.rotation.x = -2.2 * swing; parts.torso.rotation.y = 0.3 * swing; }
        else if (parts.head) { parts.head.rotation.x = 0.5 * swing; root.position.z = 0.25 * swing * H; }
        else root.position.z = 0.4 * swing * H;
      } else if (parts.torso) { parts.torso.rotation.y = 0; root.position.z = 0; }
      if (hurtT > 0) { hurtT = Math.max(0, hurtT - dt); for (const x of own) x.emissive.setRGB(hurtT > 0 ? 0.6 : 0, 0, 0); }
    }
  };
  return c;
}

// Ready-made looks for common creatures and people, by name: makeCreature(THREE, LOOKS.troll). Mix and change freely.
export const LOOKS = {
  villager: { body: 'humanoid', size: 1.75, color: '#7a5c3a', accent: '#4a4a6a', skin: '#e2b48c' },
  guard: { body: 'humanoid', size: 1.85, color: '#8a8f99', accent: '#2a3a6a', skin: '#e2b48c', weapon: 'sword', bulk: 1.1 },
  bandit: { body: 'humanoid', size: 1.8, color: '#4a3a2a', accent: '#2a2a2a', skin: '#d8a880', weapon: 'dagger', hood: true },
  troll: { body: 'humanoid', size: 2.6, color: '#6b7f4a', accent: '#4a3a24', skin: '#7f9a58', bulk: 1.5, weapon: 'club', ears: true },
  imp: { body: 'humanoid', size: 0.9, color: '#a8302a', accent: '#5a1010', skin: '#c04030', horns: true, wings: true, tail: true, eyes: '#ffdd33' },
  vampire: { body: 'humanoid', size: 1.85, color: '#1a1a22', accent: '#6a0a1a', skin: '#d9d6e0', cape: true, eyes: '#ff2222', bulk: 0.9 },
  skeleton: { body: 'humanoid', size: 1.8, color: '#e6e0cc', accent: '#cfc8b0', skin: '#ece6d2', weapon: 'sword', bulk: 0.7, eyes: '#000000' },
  mage: { body: 'humanoid', size: 1.8, color: '#3a3a8a', accent: '#2a2a5a', skin: '#e2b48c', weapon: 'staff', hood: true },
  wolf: { body: 'beast', size: 0.9, color: '#6f6a64', accent: '#3a3632' },
  bear: { body: 'beast', size: 1.3, color: '#5a3a22', accent: '#3a2414', bulk: 1.4, tail: false },
  boar: { body: 'beast', size: 0.8, color: '#5a4434', accent: '#d8d0c0', horns: true, bulk: 1.2 },
  deer: { body: 'beast', size: 1.2, color: '#9a6a3a', accent: '#3a2a1a', bulk: 0.8, horns: true },
  dragon: { body: 'dragon', size: 3.2, color: '#7a1f1f', accent: '#3a0a0a', bulk: 1.1, eyes: '#ffcc00' },
  slime: { body: 'slime', size: 0.9, color: '#5ac85a', eyes: '#103010' },
  bat: { body: 'flyer', size: 0.6, color: '#2a2224', accent: '#3a2a30', eyes: '#ff4444' }
};
