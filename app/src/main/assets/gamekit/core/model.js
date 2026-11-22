// Turns a model JSON made by game_cache or game_assets (vertices, faces, colors) into a three.js mesh with one colour per face.
// Part of PocketIDE's game kit; MIT licensed.
import * as THREE from '../engine/three.module.min.js';

export function modelGeometry(m) {
  const faces = m.faces.length / 3;
  const pos = new Float32Array(faces * 9), col = new Float32Array(faces * 12);
  const c = new THREE.Color();
  for (let f = 0; f < faces; f++) {
    c.set(m.colors[f] || '#cccccc');
    const alpha = m.opacity ? m.opacity[f] : 1;
    for (let k = 0; k < 3; k++) {
      const v = m.faces[f * 3 + k] * 3, o = f * 9 + k * 3, oc = f * 12 + k * 4;
      pos[o] = m.vertices[v]; pos[o + 1] = m.vertices[v + 1]; pos[o + 2] = m.vertices[v + 2];
      col[oc] = c.r; col[oc + 1] = c.g; col[oc + 2] = c.b; col[oc + 3] = alpha;
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  g.setAttribute('color', new THREE.BufferAttribute(col, 4));
  g.computeVertexNormals();
  g.computeBoundingSphere();
  return g;
}

export function modelMesh(m) {
  return new THREE.Mesh(modelGeometry(m), new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide, transparent: !!m.opacity }));
}

export async function loadModel(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error('Could not load ' + url);
  return modelMesh(await r.json());
}
