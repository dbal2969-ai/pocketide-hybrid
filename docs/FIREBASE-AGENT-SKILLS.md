# Firebase agent skills in PocketIDE Hybrid

PocketIDE Hybrid was built with an AI coding assistant. On 2026-11-25 we added
[Firebase's agent skills](https://firebase.google.com/docs/ai-assistance/agent-skills) to the repository. They are Google's
instruction packs that teach coding agents Firebase best practice. We then audited the app's Firebase integration against them.

```bash
npx skills add firebase/agent-skills --agent claude-code -y
```

The installed version is pinned in `skills-lock.json` (firebase/agent-skills commit `99954a3`). The skill files themselves are not
committed; `npx skills experimental_install` restores them from the lock file.

## What each skill found, and what changed

| Skill | Finding | What we did |
| --- | --- | --- |
| `firebase-ai-logic-basics` | Cloud calls should use **App Check limited-use tokens** (replay protection). Without them, models that enforce replay protection refuse the request with HTTP 403. PocketIDE used ordinary cached tokens. | `FirebaseAI.getInstance(app, googleAI(), useLimitedUseAppCheckTokens = true)` for every cloud model (`HybridAi.ai()`). The Remote Config key `hybrid_limited_use_app_check_tokens` (default true) can turn it off from the console without a new build. |
| `firebase-ai-logic-basics` | Never commit App Check debug tokens; inject them at build time. Use the debug provider in debug builds and Play Integrity in release builds. Use Gemini 3+ model names, and change models through Remote Config. | Already compliant. The judges' token is passed with `-PjudgeAppCheckToken` and never committed. `AppCheckSetup` is split by build type. The tiers are `gemini-3.5-flash-lite`, `gemini-3.8-flash` and `gemini-4-hydrogen`, and `hybrid_cloud_model` can pin one. |
| `firebase-remote-config-basics` | Use **real-time updates** so console changes reach running apps at once. | `addOnConfigUpdateListener` activates new values as they arrive and rebuilds the models. This matters for the judging week, when the free quota is shared: a pinned model or the cloud switch takes effect at once instead of after the one-hour fetch interval. |
| `firebase-security-rules-auditor` | Before: owner-only and create-only, which is good, but **no field, type or size checks**, so any signed-in user could store arbitrarily large records (resource exhaustion). Score 4 of 5 (minor). | `firestore.rules` now allows only the six fields the app writes, checks their types and values, caps string lengths, requires the server's timestamp, and still allows no update or delete. `scripts/firestore-rules-test` has 14 tests that pass in the Firestore emulator. These include another user reading or writing, a signed-out write, an extra `isAdmin` field, a 5,000-character reason, a client-set time, an update and a delete. |
| `firebase-auth-basics` | Anonymous accounts suit usage records that need no sign-in, as long as the rules check `request.auth.uid`. | Already compliant: `users/{uid}/hybrid_runs` is readable and writable only by its own anonymous user. |

## Left for the owner (console only)

1. **Firestore → Rules:** paste `firestore.rules`, or run `npx -y firebase-tools@latest deploy --only firestore:rules`.
2. **App Check → APIs → Cloud Firestore:** turn on enforcement. Rules cannot limit how many records a user writes; App Check
   enforcement makes sure only the real app can write them.
3. Optionally, **Remote Config:** add `hybrid_limited_use_app_check_tokens` (boolean, true) so it can be switched later.

Running the rules tests:

```bash
cd scripts/firestore-rules-test && npm install && npm test
```
