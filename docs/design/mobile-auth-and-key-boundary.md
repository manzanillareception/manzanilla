# Mobile key-handling and future-auth boundary (design note - docs only, nothing built)

Status: design note for review. No code, no backend, no new build follows from
this file. Live configs and the repo build path are unchanged.
Scope source: Som's Nov 6 20:03 IST steering - mobile app works with the
Cartesia key for now; later, Google sign-in approved by our own backend
decides who may use the app; "do not start the backend."

## 1. Open decision: where the Cartesia key lives today

Option A (recommended): device-local entry.
- The tester enters the key once on each test device; the app stores it
  wrapped by the Android Keystore (EncryptedSharedPreferences/DataStore).
- No shared secret in any build artifact. This matches the key-free contract
  enforced repo-wide since v17.0.41 and keeps the release byte-check honest.
- Cost: one manual entry per device.

Option B (needs explicit owner instruction after the tradeoff is heard):
embed a shared Cartesia key in the APK.
- Anyone with the APK can extract it. This is not hypothetical: the Fish
  production key was byte-verified inside the public v17.0.37 and v17.0.39
  APKs on Nov 6, and that key is still live. An embedded Cartesia key would
  repeat the same exposure with a second provider.
- Later Google-auth gating cannot un-extract a key that already shipped.
  Every APK that embeds it is a permanent distribution of the credential.

Holding position: Option B is NOT implemented. Awaiting the owner's answer to
the device-local alternative (asked by main, Nov 6 20:03).

## 2. Future-state architecture (documented, NOT built)

- The app never holds a provider key. After Google sign-in, our backend
  verifies the Google ID token, checks the allowlist, and issues a
  short-lived session token.
- The app talks only to our backend; the backend talks to Cartesia
  (short-lived token brokering or session proxy - mechanism chosen against
  current Cartesia docs at implementation time, not assumed here).
- Enforcement truth: access is NOT restricted to approved users until that
  backend exists. Until then, every build is a tester-only build and must be
  labeled as such. No UI or doc may claim access restriction before it is real.

## 3. Code boundary to keep clean now (so the migration is a swap, not a rewrite)

- All provider access sits behind one CredentialProvider seam:
  DeviceLocalCredentialProvider (today) and BackendSessionCredentialProvider
  (future). No provider key strings anywhere else.
- No credential in BuildConfig, resources, or the manifest - enforced since
  v17.0.41; the release-policy source scan plus artifact byte-checks remain
  the proof.
- Auth modeled behind an AuthGate seam: NoopAuthGate today (must surface
  "unauthenticated test build" honestly), GoogleAuthGate later.
- Nothing in this note authorizes writing that code: the mobile source for new
  work is the v27 ZIP, which is still missing. When it lands, reconcile these
  seams against the real source instead of assuming a greenfield layout.

## 4. Explicitly out of scope now

Backend code or deployment, Google auth SDK wiring, any store/internal
distribution, any new release, and any change to the live nightdesk or
interpreter agents.
