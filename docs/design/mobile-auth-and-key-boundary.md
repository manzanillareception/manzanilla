# Mobile credentials and authentication phases (design note)

This document defines integration boundaries; it does not implement mobile
Cartesia access, deploy a backend or change live agent configuration.

## V1 product and source baseline

- OpenAI provides live translation; Cartesia provides guest reception only.
- No Google sign-in or app account login is required for V1. Reception starts
  only through the reception button or a verified physical handset signal.
- The recovered `Manzanilla-Source-for-V2-2026-10-30.zip` contains the v27 app:
  `com.agentdeck`, version `1.4.7-reception-themes` / code 27. It has an OpenAI
  implementation and on-device key storage. It is distinct from the repository's
  legacy `dev.orange.hotel` app. Preserve the correct package, signer and user data
  when integrating; the archive does not include the original signing key.
- Gama 804 handset detection remains **UNVERIFIED HARDWARE**.

## Open owner decision: Cartesia credentials and transport

Neither option below is selected by this note. Keep a small provider interface
so the owner can choose without rewriting reception behavior.

**Option A — device-local BYOK.** The owner enters their Cartesia key on the
device. Store it with Android Keystore-backed encryption, mask it after entry,
and support replacement and removal. Keep it out of logs, analytics, backups,
source and build artifacts. A storage container alone does not provide encryption.
Transmit it only to the intended provider over TLS.

**Option B — authenticated backend relay, a later phase.** The device uses an
approved relay interface while the provider key remains server-side. Specify and
verify client authentication, authorization, session limits and revocation before
deployment. The current V2 Google/admin flow is a separate phase; do not silently
introduce it into the V1 guest flow or expose an unauthenticated shared-key relay.

Embedding a shared provider key in an APK is prohibited. It is not a third option:
anyone holding that APK can extract the credential, and later login cannot undo
its distribution. Historical credential exposure does not justify repeating it.

## V2 source status and enforcement

The separate `manzanilla-web/src/v2` backend source is already on main. It verifies
Google identity, requires admin-approved sessions, and gates the Cartesia relay.
Its source and automated tests are evidence of the implemented backend design,
not a deployed service or a working mobile integration.

Google sign-in, centralized account approval and the admin dashboard belong to
V2. Until a chosen authentication path is deployed and verified end to end, do
not claim that mobile access is centrally restricted or that revocation stops a
live call. Deployment requires the appropriate ownership, origins and secrets.

## Proposed mobile boundaries and validation

- A reception provider interface separates device-local credentials from relay
  sessions. These mobile adapters are proposed, not claimed to exist today.
- Model authentication separately: V1 has no cloud-login gate; V2 may use an
  authenticated session gate after its own integration and acceptance checks.
- Keep OpenAI translation and Cartesia reception lifecycles separate, with an
  explicit microphone/speaker ownership rule. Neither can hijack the other.
- Preserve existing encrypted OpenAI entries and their Keystore alias across
  updates. Verify replacement/removal, error handling and retained data on device.
- Inspect source and the final APK for bundled credentials. A source-pattern
  scan alone is not proof that an artifact contains no secret.

Merging this note does not select a transport or authorize provider calls,
deployment, publication, distribution or a live agent configuration change.
