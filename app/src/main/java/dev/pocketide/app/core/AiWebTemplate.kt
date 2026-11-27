package dev.pocketide.app.core

/**
 * 1.5.214: "Hybrid AI web app", a starter for apps that use Firebase AI Logic themselves. Its ai.js answers with:
 *  - PocketIDE's own Hybrid AI while it runs in PocketIDE's Preview (the PocketIDEAI bridge, PreviewAi): Gemini Nano on this phone
 *    first, Gemini in the cloud when the phone cannot, through PocketIDE's Firebase AI Logic. No setup.
 *  - Firebase AI Logic's web SDK everywhere else, in hybrid mode (PREFER_ON_DEVICE): Gemini Nano in desktop Chrome 139+ where the
 *    Prompt API is on, the cloud model elsewhere. It needs the app's own Firebase project (firebase-config.js) and App Check.
 * The JavaScript has no "$" in it, so the Kotlin strings need no escapes.
 */
object AiWebTemplate {
    /** The Firebase web SDK version the starter loads (13.3.0, 2026-11-11; on-device inference needs 12.5.0 or later). */
    const val SDK = "13.3.0"

    val files: Map<String, String> = linkedMapOf(
        "index.html" to """
            |<!doctype html>
            |<html lang="en">
            |<head>
            |  <meta charset="utf-8">
            |  <meta name="viewport" content="width=device-width, initial-scale=1">
            |  <title>{{name}}</title>
            |  <link rel="stylesheet" href="style.css">
            |</head>
            |<body>
            |  <main>
            |    <h1>{{name}}</h1>
            |    <p class="sub">Ask anything. The answer says where it ran: on the device (Gemini Nano) or in the cloud.</p>
            |    <textarea id="prompt" rows="3" placeholder="Explain what a closure is in one sentence."></textarea>
            |    <button id="ask">Ask</button>
            |    <div id="answer" class="answer"></div>
            |    <div id="where" class="where"></div>
            |  </main>
            |  <script type="module" src="app.js"></script>
            |</body>
            |</html>
            |""".trimMargin(),
        "style.css" to """
            |body { margin: 0; font-family: system-ui, sans-serif; background: #f6f7fb; color: #1d2330; }
            |main { max-width: 640px; margin: 0 auto; padding: 24px 16px; }
            |h1 { margin: 0 0 4px; font-size: 1.6rem; }
            |.sub { margin: 0 0 16px; color: #5b6475; }
            |textarea { width: 100%; box-sizing: border-box; padding: 12px; font: inherit; border: 1px solid #c9cfdb; border-radius: 10px; }
            |button { margin-top: 10px; padding: 10px 22px; font: inherit; border: 0; border-radius: 10px; background: #3451e0; color: #fff; }
            |button:disabled { opacity: 0.6; }
            |.answer { margin-top: 18px; white-space: pre-wrap; line-height: 1.5; }
            |.where { margin-top: 8px; font-size: 0.85rem; color: #5b6475; }
            |""".trimMargin(),
        "app.js" to """
            |import { ask } from './ai.js';
            |
            |const prompt = document.getElementById('prompt');
            |const button = document.getElementById('ask');
            |const answer = document.getElementById('answer');
            |const where = document.getElementById('where');
            |
            |button.addEventListener('click', async () => {
            |  const text = prompt.value.trim() || prompt.placeholder;
            |  button.disabled = true;
            |  answer.textContent = 'Thinking…';
            |  where.textContent = '';
            |  try {
            |    const r = await ask(text);
            |    answer.textContent = r.text;
            |    where.textContent = (r.onDevice ? '⚡ On the device (Gemini Nano)' : '☁ In the cloud (Gemini)') +
            |      (r.seconds ? ' · ' + r.seconds.toFixed(1) + ' s' : '') + ' · ' + r.via;
            |  } catch (e) {
            |    answer.textContent = 'No answer: ' + (e && e.message ? e.message : e);
            |  } finally {
            |    button.disabled = false;
            |  }
            |});
            |""".trimMargin(),
        "ai.js" to """
            |// One ask(prompt) for the whole app. It returns { text, onDevice, seconds, via }.
            |//
            |// In PocketIDE's Preview on the phone, PocketIDE answers through its own Firebase AI Logic: Gemini Nano on the phone
            |// first, Gemini in the cloud when the phone cannot. Nothing to set up.
            |// Everywhere else (a browser, a published site), Firebase AI Logic's web SDK answers in hybrid mode: Gemini Nano in
            |// desktop Chrome 139+ with the Prompt API on, the cloud model elsewhere. Fill in firebase-config.js first.
            |import { firebaseConfig, appCheckSiteKey, useAppCheckDebugToken } from './firebase-config.js';
            |
            |const SDK = 'https://www.gstatic.com/firebasejs/${SDK}/';
            |
            |function askPocketIDE(prompt) {
            |  return new Promise((resolve, reject) => {
            |    const id = String(Date.now()) + Math.random().toString(36).slice(2);
            |    window.__pocketideAIWaiting = window.__pocketideAIWaiting || {};
            |    window.__pocketideAIWaiting[id] = { resolve, reject };
            |    window.__pocketideAI = window.__pocketideAI || ((rid, json) => {
            |      const w = window.__pocketideAIWaiting[rid];
            |      if (!w) return;
            |      delete window.__pocketideAIWaiting[rid];
            |      const r = JSON.parse(json);
            |      if (r.error) w.reject(new Error(r.error));
            |      else w.resolve({ text: r.text, onDevice: r.onDevice, seconds: r.seconds, via: 'PocketIDE Hybrid AI (Firebase AI Logic)' });
            |    });
            |    window.PocketIDEAI.ask(id, prompt);
            |  });
            |}
            |
            |let model = null;
            |
            |async function webModel() {
            |  if (model) return model;
            |  if (!firebaseConfig) throw new Error('Add your Firebase project to firebase-config.js (see README.md).');
            |  const { initializeApp } = await import(SDK + 'firebase-app.js');
            |  const { getAI, getGenerativeModel, GoogleAIBackend, InferenceMode } = await import(SDK + 'firebase-ai.js');
            |  const app = initializeApp(firebaseConfig);
            |  if (useAppCheckDebugToken) self.FIREBASE_APPCHECK_DEBUG_TOKEN = true;
            |  if (appCheckSiteKey || useAppCheckDebugToken) {
            |    const { initializeAppCheck, ReCaptchaEnterpriseProvider } = await import(SDK + 'firebase-app-check.js');
            |    initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(appCheckSiteKey || 'debug'), isTokenAutoRefreshEnabled: true });
            |  }
            |  const ai = getAI(app, { backend: new GoogleAIBackend() });
            |  model = getGenerativeModel(ai, { mode: InferenceMode.PREFER_ON_DEVICE });
            |  // Starts Gemini Nano's download in Chrome where it is offered (it must follow a tap; it does, as ask runs from one).
            |  try { model.initializeDeviceModel(); } catch (e) { /* the cloud answers meanwhile */ }
            |  return model;
            |}
            |
            |export async function ask(prompt) {
            |  if (window.PocketIDEAI) return askPocketIDE(prompt);
            |  const m = await webModel();
            |  const t0 = performance.now();
            |  const result = await m.generateContent(prompt);
            |  const r = result.response;
            |  return { text: r.text(), onDevice: r.inferenceSource === 'on_device', seconds: (performance.now() - t0) / 1000,
            |    via: 'Firebase AI Logic (web, hybrid)' };
            |}
            |""".trimMargin(),
        "firebase-config.js" to """
            |// Your Firebase project's web app config (Firebase console → Project settings → Your apps → Web app).
            |// Not needed inside PocketIDE's Preview, where PocketIDE answers. Leave it null until you publish the app.
            |export const firebaseConfig = null;
            |
            |// Firebase AI Logic needs App Check. For a published site: your reCAPTCHA Enterprise site key.
            |export const appCheckSiteKey = '';
            |// While testing in your own browser: true, then register the debug token the console prints
            |// (Firebase console → App Check → Manage debug tokens). Never publish with this on.
            |export const useAppCheckDebugToken = false;
            |""".trimMargin(),
        "README.md" to """
            |# {{name}}
            |
            |A web app whose AI runs on Firebase AI Logic's hybrid inference: on the device first, in the cloud when it must.
            |
            |- **In PocketIDE's Preview**, PocketIDE answers through its own Firebase AI Logic: Gemini Nano on this phone first,
            |  Gemini in the cloud when the phone cannot. Nothing to set up.
            |- **In a browser or a published site**, `ai.js` uses the Firebase AI Logic web SDK (`firebase/ai` ${SDK}) in
            |  `PREFER_ON_DEVICE` mode: Gemini Nano in desktop Chrome 139 or later where the Prompt API is turned on, and the cloud
            |  model (gemini-3.5-flash-lite by default) everywhere else. Fill in `firebase-config.js` with your own Firebase project
            |  and set up App Check (reCAPTCHA Enterprise) first.
            |
            |Every answer says where it ran. `ask(prompt)` in `ai.js` is the one function the rest of the app uses.
            |
            |On-device limits (web): single-turn text (and JPEG/PNG images), about 9,000 tokens, no tools or search grounding.
            |""".trimMargin(),
    )

    val TEMPLATE = Template("ai-web", "Hybrid AI web app", "A web page that asks Gemini through Firebase AI Logic: on the device first, the cloud when it must. " +
        "In Preview it uses PocketIDE's Gemini Nano.", files, "app.js")
}
