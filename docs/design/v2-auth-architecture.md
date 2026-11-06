# V2 auth architecture: Google sign-in + admin approval, keys server-side only

Status: implemented as code on a branch, NOT DEPLOYED. The live manz-webtest
worker and the old Fish-line backend are untouched.
Scope source: Som's Nov 6 20:12 IST message - Google login + backend admin
approval, no API keys pasted or built into the APK, "completely unhackable in
version 2". Per main's 20:12 reply to him: no system is completely unhackable;
security is treated as something to test, and that is what the test suite does.

## Shape

- New worker `manz-v2` (manzanilla-web/wrangler.v2.toml, src/v2/*). Separate
  name: the live pilot worker is never touched by this work.
- Provider keys live only in worker secrets (CARTESIA_API_KEY). Clients, web
  or mobile, never receive a provider key. The APK ships with no key.
- Access rule: Google sign-in, then owner approval. Both are enforced
  server-side on every session open; a revoked user cannot open a new session
  even with a fresh Google token, and revocation clears any active lease.
- Only the Cartesia night-receptionist agent is exposed. OpenAI translation is
  parked per the owner's 20:02 steering and is not wired here.

## Flow

1. Client signs in with Google (web: GIS button on /v2/login; Android:
   GoogleSignIn SDK) and sends the Google ID token to POST /v2/auth.
2. The worker verifies the token: RS256 signature against Google's JWKS,
   issuer, audience (configured client IDs), expiry.
3. First sign-in creates a user row in state `pending`. Admin emails
   (ADMIN_EMAILS var) are auto-approved.
4. Approved users get a 15-minute HMAC session token. Pending/revoked get 403.
5. WSS /v2/relay: client sends {type:'auth',token} as the first message
   (no token in the URL). The worker re-checks approval, enforces one active
   session per user and a daily cap, then bridges to the Cartesia agent
   websocket, adding the API key only on the upstream handshake.
6. Admin: /v2/admin page + /v2/admin/{list,approve,revoke,audit} endpoints,
   gated by a fresh Google token whose email is on the admin allowlist. All
   decisions are audit-logged (registration, approval, revocation).

## Mobile contract (for the v27 source when it lands)

- Android obtains an ID token via Google Sign-In (serverClientId = the web
  OAuth client ID) and calls POST /v2/auth; on 403 it shows "access pending".
- With a session token it opens WSS /v2/relay and sends the auth message
  first; PCM audio framing then matches the existing relay contract.
- Activation (button press / handset pickup per the 20:02 scope) is a
  client-side gate in front of this flow; the mechanism is still TBD and is
  not invented here.

## Security properties and honest limits

- Tested (tests/v2, 23 cases, run in CI): forged/expired/wrong-audience/
  wrong-issuer tokens, tampered payloads, unknown signing keys, tampered and
  expired session tokens, wrong-secret sessions, non-admin admin calls,
  pending and revoked denial, revocation clearing an active lease, one-active
  session and daily-cap enforcement.
- Session tokens expire in 15 minutes; revocation takes effect on the next
  session open (an in-flight relay is not killed mid-call yet - recorded as
  follow-up, since mid-call kill needs a status poll inside the bridge).
- Not covered by this design: compromise of a user's Google account itself,
  device compromise, phishing. These are outside any app-level design.
- The relay bridge pattern mirrors the existing proven pilot proxy; the
  live-call audio path still needs a real deployment test before anyone calls
  mobile Cartesia "working".

## Open inputs (owner)

- Google OAuth client IDs (web + Android) from the owner's Google Cloud
  project; creating the project/clients is free but needs his Google account.
- Deploy decision for manz-v2 (workers.dev free tier; new worker, does not
  touch the live pilot).
- v27 mobile source ZIP (unchanged blocker for the app side).
- Hotel identity/address/hours for the receptionist prompt (unchanged).
