# Retired speech-provider removal — 17.0.42 / 72

This candidate removes the previous voice-provider integration. It does not add a replacement voice implementation to the legacy `dev.orange.hotel` application. Translation requests fail with a visible unavailable message before microphone permission, capture or provider access. Staff-led hotel operations and the updater remain available.

Removed paths include native provider transport/configuration and key setup, managed backend session endpoints and lease provisioning, the provider-backed LiveKit worker, web-pilot session minting, and provider-specific diagnostic/probe scripts and CI jobs. Historical documentation, verification records, third-party notices and Git history are retained.

The recovered v27 `com.agentdeck` source and its OpenAI implementation are separate from this app and are unchanged. `manzanilla-web/src/v2` retains the existing Cartesia/auth backend. This removal neither deploys a backend nor changes an installed device.

Existing database tables and unrelated encrypted settings are not erased. A new backend database no longer provisions the retired provider's session ledger. Original Android package/signing configuration and the existing updater stay in place; versionName/versionCode increase from 17.0.41/71 to 17.0.42/72.

## Verification

- Full Python suite: **194 passed**, one existing dependency deprecation warning.
- Node suites: **21 passed**, including retired web-route and retained Cartesia behavior checks.
- Reception asset generation/check, release-policy credential/package guard, JavaScript syntax, shell syntax and `git diff --check`: passed.
- Live Cartesia V2 source/config has no diff.
- Android release/unit/lint/instrumentation compilation was attempted locally; build verification remains pending because Gradle could not download its distribution in the current runtime. No signed APK was produced.
- Browser checks were attempted with Chromium 151. The runtime rejects its process socket with `Operation not permitted`, so browser scenarios did not execute here. GitHub's existing Android/browser jobs remain required.
- No physical-device tests, production data changes or provider calls were performed.

The only production references to the retired provider name are the explicit one-time deletion of its old encrypted credential/preference identifiers in `RetiredCredentials.kt`. A corrupt old value can be erased without loading or decrypting it. Its isolated instrumentation fixture verifies retention of hotel credentials and the shared Keystore alias. Historical provider names in regression fixtures and documents are intentionally retained.

 A local build does not establish real-device voice behavior, physical handset detection or retained-data release updates. No provider calls or production data are used in verification.
