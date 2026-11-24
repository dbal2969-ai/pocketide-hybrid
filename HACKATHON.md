# PocketIDE Hybrid — Firebase #BuildHybridAI 2026

PocketIDE is a coding environment that runs on an Android phone: editor, terminal, git, previews and an AI agent.
This repository is the MIT edition of PocketIDE with **hybrid AI** added for the
[#BuildHybridAI hackathon](https://firebasehybridaihackathon.devpost.com/): quick coding help runs **on the phone with
Gemini Nano** through **Firebase AI Logic**, and falls back to a **cloud Gemini model** when Nano is missing,
the request is too long for it, or the person prefers the cloud.

## What was there before the hackathon

The first commit (`Baseline: PocketIDE MIT edition 1.5.182…`) is the app exactly as it was before the submission period
(private repository `test-coder2.2`, commit `1013cb7`). Everything after it was built during the hackathon, so
`git log` and `git diff <baseline>..main` show precisely what changed.

## What was built during the hackathon

| Change | Files |
|---|---|
| Firebase AI Logic hybrid inference: one `GenerativeModel` per routing mode (`PREFER_ON_DEVICE`, `PREFER_IN_CLOUD`, `ONLY_ON_DEVICE`), cloud model `gemini-3.1-flash-lite` | `app/src/main/java/dev/pocketide/app/HybridAi.kt` |
| Routing rule on top of the SDK: requests over 4,000 characters go to the cloud even in on-phone-first mode (Gemini Nano is built for short single-turn prompts) | `HybridAi.route` |
| Every reply is labelled with where it ran (`inferenceSource`: on this phone or cloud) and how long it took | `HybridScreen.label` |
| Gemini Nano status and one-tap download through Android AICore, with progress | `HybridAi.nanoStatus`, `HybridAi.downloadNano`, Hybrid AI screen |
| Hybrid AI screen: Nano status, routing mode, a prompt to try | `HybridScreen.kt`, opened from Models → Hybrid AI |
| Editor **Quick assist** (⋮ menu): explain the selected lines, look for bugs, write a doc comment (insert above), draft a commit message — Nano first; “Hand to the agent” escalates the same request to PocketIDE's full agent | `EditorPane.kt`, `HybridScreen.quickAssist` |
| App Check: debug provider in debug builds (token can be copied from the Hybrid AI screen), Play Integrity in release builds | `app/src/debug/.../AppCheckSetup.kt`, `app/src/release/.../AppCheckSetup.kt` |
| Hybrid AI in PocketIDE's model list, for chat and the agent, with or without a project | `core/Providers.kt`, `ModelClient.kt` |
| The agent gets real tool calls on both paths: Gemini Nano in small local model mode (one JSON action per reply, core tools) parsed into tool calls; the cloud model through Firebase AI Logic function calling with every PocketIDE tool | `HybridAgent.kt` |
| Gemini Nano passes on what it cannot do (needs the web, too long, refuses, or claims an edit that never happened) and says so in the chat; the request goes to the person's strong model when one is set, else the cloud model | `core/HybridRoute.kt`, `HybridAgent.kt`, `ModelClient.kt` |
| Settings: temperature, top-K, max output tokens, the on-phone limit, and the Fast (Gemma 4 E2B) or Full (E4B) on-phone model | `core/HybridSettings.kt`, `HybridScreen.kt` |
| **Remote Config**: the cloud model, the default on-phone limit and routing mode, and a switch that turns the cloud off, without an app update | `HybridCloud.kt` |
| **Authentication** (anonymous) and **Firestore**: a per-person record of where each request ran, how long it took and why Nano passed it on (never prompts or code), shown as "Your hybrid usage" | `HybridCloud.kt`, `core/HybridStats.kt` |
| The agent also saves code written in `language:file.ext` fences, which small models often use | `core/ReplyParser.kt` |
| Build: Firebase is applied only when `app/google-services.json` exists; CI writes it from the `GOOGLE_SERVICES_JSON` secret, so the config is not in git and the app still builds without it | `build.gradle.kts`, `app/build.gradle.kts`, `.github/workflows/build-apk.yml`, `.gitignore` |

## Why hybrid fits a phone IDE

- **Private by default.** Code under the cursor can be explained or reviewed without leaving the phone (`ONLY_ON_DEVICE` never sends it out).
- **Works offline.** On a train or a plane, quick help still answers.
- **Free and fast for small things.** Explaining ten lines does not need a cloud model or an API key.
- **The cloud is there when it matters.** Long files and phones without Nano still get an answer through the same API, and the
  full agent remains one tap away for multi-step work.

## Set up your own build

1. Create a Firebase project, add an Android app with package `dev.pocketide.app`, and download `google-services.json` into `app/`.
2. Firebase console → **AI Logic** → Get started → **Gemini Developer API**.
3. Firebase console → **App Check** → register the app (Play Integrity); for a debug build, add the debug token shown in the
   app (Models → Hybrid AI → Copy App Check debug token) under *Manage debug tokens*.
4. Firebase console → **Authentication** → Sign-in method → turn on **Anonymous**.
5. Firebase console → **Firestore Database** → Create database, then Rules:
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{db}/documents {
       match /users/{uid}/hybrid_runs/{run} {
         allow read, create: if request.auth != null && request.auth.uid == uid;
       }
     }
   }
   ```
6. Firebase console → **Remote Config** (optional; the app has defaults for all four): `hybrid_cloud_model` (string),
   `hybrid_on_device_max_chars` (number), `hybrid_default_mode` (`PREFER_ON_DEVICE`, `PREFER_IN_CLOUD` or `ONLY_ON_DEVICE`),
   `hybrid_cloud_enabled` (boolean).
7. `./gradlew assembleDebug` (needs the Android SDK and NDK; see `.github/workflows/build-apk.yml`).

On-device inference needs a phone that supports Gemini Nano through Android AICore (for example recent Pixel and Samsung
Galaxy S flagships). Other phones use the cloud model automatically.

## Licence

MIT (see `LICENSE`); third-party notices in `NOTICE.md` and `LICENSES/`.
