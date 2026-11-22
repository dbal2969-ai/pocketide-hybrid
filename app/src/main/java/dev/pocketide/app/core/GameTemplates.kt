package dev.pocketide.app.core

/** Game templates: a small working game on a bundled engine (Phaser for 2D, Three.js for 3D). The engine files are copied into engine/ and work offline. */
object GameTemplates {
    const val ENGINE_DIR = "engine/"

    const val NOTE = "This project has a game engine in engine/ (do not open, read or edit anything in that folder; it is a minified library). " +
        "A camera zoom moves everything fixed to the screen (scrollFactor 0: score, buttons, panels) off-screen, so draw such a HUD with a second unzoomed camera (cameras.add) that the main camera ignores. " +
        "Keep window.render_game_to_text() (a short JSON of the game state) up to date, and call look_at_preview after visual changes. " +
        "Use the engine's real API only; if you are not sure a function exists, build the effect with the basic calls you are sure of."

    private val LICENSES_ASSETS = linkedMapOf(
        "engine/PHASER-LICENSE.txt" to "engine/PHASER-LICENSE.txt",
        "engine/THREE-LICENSE.txt" to "engine/THREE-LICENSE.txt"
    )

    private const val HEAD = "<!doctype html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">\n<title>{{name}}</title>\n<style>html,body{margin:0;height:100%;background:#0b1020;overflow:hidden;touch-action:none}</style>\n</head>\n<body>\n"

    private val PHASER_GAME = """
class Main extends Phaser.Scene {
  constructor() { super('Main'); }

  create() {
    // Graphics are drawn in code, so the game needs no image files.
    const g = this.add.graphics();
    g.fillStyle(0x4cc9f0, 1); g.fillRoundedRect(0, 0, 44, 44, 10); g.generateTexture('player', 44, 44); g.clear();
    g.fillStyle(0xffd166, 1); g.fillCircle(11, 11, 11); g.generateTexture('coin', 22, 22); g.destroy();

    this.player = this.physics.add.image(this.scale.width / 2, this.scale.height - 80, 'player').setCollideWorldBounds(true);
    this.coins = this.physics.add.group();
    this.score = 0;
    this.label = this.add.text(12, 12, 'Score 0', { fontSize: '22px', color: '#ffffff' });
    this.add.text(this.scale.width / 2, this.scale.height - 24, 'Touch and drag to move', { fontSize: '14px', color: '#8899bb' }).setOrigin(0.5);

    this.time.addEvent({
      delay: 800, loop: true,
      callback: () => this.coins.create(Phaser.Math.Between(20, this.scale.width - 20), -12, 'coin').setVelocityY(150)
    });
    this.physics.add.overlap(this.player, this.coins, (player, coin) => {
      coin.destroy();
      this.score += 1;
      this.label.setText('Score ' + this.score);
    });
    this.cursors = this.input.keyboard.createCursorKeys();

    // Lets PocketIDE's page check read the game as text. Keep it up to date as the game grows.
    window.render_game_to_text = () => JSON.stringify({
      score: this.score,
      player: { x: Math.round(this.player.x), y: Math.round(this.player.y) },
      coinsFalling: this.coins.countActive()
    });
  }

  update() {
    const pointer = this.input.activePointer;
    if (pointer.isDown) {
      this.player.setVelocityX(Phaser.Math.Clamp((pointer.x - this.player.x) * 8, -420, 420));
    } else if (this.cursors.left.isDown) {
      this.player.setVelocityX(-320);
    } else if (this.cursors.right.isDown) {
      this.player.setVelocityX(320);
    } else {
      this.player.setVelocityX(0);
    }
    this.coins.getChildren().forEach((coin) => { if (coin.y > this.scale.height + 20) coin.destroy(); });
  }
}

new Phaser.Game({
  type: Phaser.AUTO,
  backgroundColor: '#0b1020',
  scale: { mode: Phaser.Scale.FIT, autoCenter: Phaser.Scale.CENTER_BOTH, width: 360, height: 640 },
  physics: { default: 'arcade', arcade: { gravity: { y: 0 } } },
  scene: [Main]
});
""".trimStart()

    private val THREE_GAME = """
import * as THREE from './engine/three.module.min.js';

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0b1020);

const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 100);
camera.position.set(0, 1.5, 5);

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setSize(window.innerWidth, window.innerHeight);
document.body.appendChild(renderer.domElement);

scene.add(new THREE.HemisphereLight(0xbfd8ff, 0x203050, 1.1));
const sun = new THREE.DirectionalLight(0xffffff, 1.4);
sun.position.set(3, 5, 2);
scene.add(sun);

const cube = new THREE.Mesh(new THREE.BoxGeometry(1.6, 1.6, 1.6), new THREE.MeshStandardMaterial({ color: 0x4cc9f0, roughness: 0.4 }));
cube.position.y = 0.4;
scene.add(cube);

const floor = new THREE.Mesh(new THREE.PlaneGeometry(20, 20), new THREE.MeshStandardMaterial({ color: 0x16203a }));
floor.rotation.x = -Math.PI / 2;
floor.position.y = -0.4;
scene.add(floor);

// Touch and drag to turn the cube.
let dragging = false, lastX = 0, lastY = 0;
renderer.domElement.addEventListener('pointerdown', (e) => { dragging = true; lastX = e.clientX; lastY = e.clientY; });
window.addEventListener('pointerup', () => { dragging = false; });
window.addEventListener('pointermove', (e) => {
  if (!dragging) return;
  cube.rotation.y += (e.clientX - lastX) * 0.01;
  cube.rotation.x += (e.clientY - lastY) * 0.01;
  lastX = e.clientX; lastY = e.clientY;
});

window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

// Lets PocketIDE's page check read the scene as text. Keep it up to date as the game grows.
window.render_game_to_text = () => JSON.stringify({ cubeTurn: +cube.rotation.y.toFixed(2), dragging });

renderer.setAnimationLoop(() => {
  if (!dragging) cube.rotation.y += 0.01;
  renderer.render(scene, camera);
});
""".trimStart()

    val GAME2D = Template("game2d", "2D game (Phaser)", "A small coin-catcher on the Phaser engine (bundled, works offline). Runs in the Preview tab.", linkedMapOf(
        "index.html" to HEAD + "<script src=\"engine/phaser.min.js\"></script>\n<script src=\"game.js\"></script>\n</body>\n</html>\n",
        "game.js" to PHASER_GAME,
        "README.md" to "# {{name}}\n\nA 2D game built on [Phaser](https://phaser.io) 3.90 (MIT). The engine is in `engine/`; leave it alone and put your own code in `game.js` and new files you add.\n\nOpen the Preview tab to play.\n"
    ), "game.js", LICENSES_ASSETS + ("engine/phaser.min.js" to "engine/phaser.min.js"))

    val GAME3D = Template("game3d", "3D game (Three.js)", "A turnable 3D scene on the Three.js engine (bundled, works offline). Runs in the Preview tab.", linkedMapOf(
        "index.html" to HEAD + "<script type=\"module\" src=\"game.js\"></script>\n</body>\n</html>\n",
        "game.js" to THREE_GAME,
        "README.md" to "# {{name}}\n\nA 3D scene built on [Three.js](https://threejs.org) r170 (MIT). The engine is in `engine/`; leave it alone and put your own code in `game.js` and new files you add.\n\nFiles you add load the engine with `import * as THREE from './engine/three.module.min.js';`.\n\nOpen the Preview tab to see it.\n"
    ), "game.js", LICENSES_ASSETS + ("engine/three.module.min.js" to "engine/three.module.min.js"))

    /** True for files that belong to the bundled engine: they are never read by the assistant or the page checks. */
    fun isEngineFile(path: String): Boolean = path.startsWith(ENGINE_DIR)
}
