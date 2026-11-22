// Third-person 3D walker (three.js): move with the joystick or arrow keys, collect the gems, bump into the crates.
// Your own character: export or import a model to data/models/player.json (game_assets action=model) and it replaces the placeholder.
import * as THREE from './engine/three.module.min.js';
import { Loop } from './core/loop.js';
import { Input } from './core/input.js';
import { text } from './core/ui.js';
import { modelMesh } from './core/model.js';

const view = document.getElementById('view'), hud = document.getElementById('hud'), hctx = hud.getContext('2d');
const renderer = new THREE.WebGLRenderer({ canvas: view, antialias: true });
const scene = new THREE.Scene(); scene.background = new THREE.Color(0x8fc4ee); scene.fog = new THREE.Fog(0x8fc4ee, 25, 70);
const camera = new THREE.PerspectiveCamera(55, 1, 0.1, 200);
function fit() { renderer.setPixelRatio(Math.min(devicePixelRatio, 2)); renderer.setSize(innerWidth, innerHeight, false); hud.width = innerWidth; hud.height = innerHeight; camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); }
addEventListener('resize', fit); fit();

scene.add(new THREE.HemisphereLight(0xffffff, 0x556644, 0.9));
const sun = new THREE.DirectionalLight(0xffffff, 0.8); sun.position.set(8, 14, 6); scene.add(sun);
const ground = new THREE.Mesh(new THREE.PlaneGeometry(120, 120), new THREE.MeshLambertMaterial({ color: 0x5c9c4c })); ground.rotation.x = -Math.PI / 2; scene.add(ground);
scene.add(new THREE.GridHelper(120, 60, 0x4a8a3c, 0x4a8a3c));

const crates = [];
for (let i = 0; i < 14; i++) {
  const a = i * 2.4, r = 6 + (i % 5) * 3.2, s = 1.2 + (i % 3) * 0.5;
  const m = new THREE.Mesh(new THREE.BoxGeometry(s, s, s), new THREE.MeshLambertMaterial({ color: 0xa0703c + i * 0x050200 }));
  m.position.set(Math.cos(a) * r, s / 2, Math.sin(a) * r); scene.add(m); crates.push({ x: m.position.x, z: m.position.z, h: s / 2 + 0.4 });
}
const gems = [];
for (let i = 0; i < 8; i++) {
  const a = i * 0.9 + 0.3, r = 5 + (i % 4) * 4;
  const m = new THREE.Mesh(new THREE.OctahedronGeometry(0.45), new THREE.MeshLambertMaterial({ color: 0x3be0ff, emissive: 0x0a4a66 }));
  m.position.set(Math.cos(a) * r, 0.9, Math.sin(a) * r); scene.add(m); gems.push(m);
}

const player = new THREE.Group(); scene.add(player);
const body = new THREE.Mesh(new THREE.CylinderGeometry(0.4, 0.4, 1.4, 12), new THREE.MeshLambertMaterial({ color: 0xf0f0f0 })); body.position.y = 0.7; player.add(body);
const nose = new THREE.Mesh(new THREE.BoxGeometry(0.2, 0.2, 0.5), new THREE.MeshLambertMaterial({ color: 0xcc3333 })); nose.position.set(0, 1.1, 0.4); player.add(nose);
fetch('data/models/player.json').then(r => r.ok ? r.json() : null).then(j => {
  if (!j) return;
  const m = modelMesh(j); m.geometry.computeBoundingBox(); const b = m.geometry.boundingBox, k = 1.6 / (b.max.y - b.min.y);
  m.scale.setScalar(k); m.position.y = -b.min.y * k; player.remove(body); player.remove(nose); player.add(m);
}).catch(() => {});

const input = new Input(hud); let heading = 0, score = 0;
function update(dt) {
  const a = input.axis(), speed = 6;
  if (a.x || a.y) {
    const mx = a.x * speed * dt, mz = a.y * speed * dt;
    player.position.x += mx; player.position.z += mz;
    heading = Math.atan2(a.x, a.y);
    for (const c of crates) { const dx = player.position.x - c.x, dz = player.position.z - c.z, d = Math.hypot(dx, dz); if (d < c.h + 0.4) { const push = (c.h + 0.4 - d) / (d || 1); player.position.x += dx * push; player.position.z += dz * push; } }
  }
  player.rotation.y += (heading - player.rotation.y) * Math.min(1, dt * 10);
  gems.forEach((g, i) => { if (!g.visible) return; g.rotation.y += dt * 2; g.position.y = 0.9 + Math.sin(performance.now() / 300 + i) * 0.15;
    if (Math.hypot(g.position.x - player.position.x, g.position.z - player.position.z) < 0.9) { g.visible = false; score++; } });
  camera.position.lerp(new THREE.Vector3(player.position.x, 7, player.position.z + 9), 1 - Math.exp(-5 * dt));
  camera.lookAt(player.position.x, 1, player.position.z);
  input.endFrame();
}
function render() {
  renderer.render(scene, camera);
  hctx.clearRect(0, 0, hud.width, hud.height);
  text(hctx, 'Gems ' + score + '/' + gems.length, 10, 10, { size: 18, bold: true });
  input.draw(hctx);
}
window.render_game_to_text = () => JSON.stringify({ player: { x: +player.position.x.toFixed(2), z: +player.position.z.toFixed(2) }, gems: score, total: gems.length });
new Loop(update, render).start();
