# Device management (V2 fleet management, hotel app lane)

This module implements the Android side of the frozen `/api/v1` device-management contract
(see the coordinated contract document owned by the backend lane). Everything lives under
`android/hotel/`; no shared contract, migration, web-admin, or CI ownership files are touched.

## What runs on the tablet

- **Enrollment** (`ManagementSync`, `ManagementClient`, staff screen section in `KeyEntryActivity`):
  primary path is hotel staff email/password sign-in against Supabase Auth
  (`BuildConfig.SUPABASE_URL` / `SUPABASE_ANON_KEY`, public-by-design client config plumbed in
  release builds), then `POST /api/v1/devices/enroll` with the hotel JWT. The admin-assisted
  fallback is a one-use setup code sent as `setupToken`. Enrollment stores
  `{base, deviceId, deviceCredential}` in encrypted native storage (SecretStore
  `management_connection`, separate from the legacy `hotel_connection`) plus the process
  `bootId` and policy state in the `management` SharedPreferences. Staff can remove enrollment
  from the same screen; removal clears the connection, queue and policy state.
- **Heartbeat** every 30 s while the app is foreground (never in background): carries
  bootId, sequence, build, mode and microphone state; the response drives policy.
- **Policy apply + ACK**: desired/effective access and policy revision from enroll and
  heartbeat responses. A revision increase emits `policy.ack` via the batch route. Blocked
  effective access stops a live voice session; new starts are denied with guest-safe copy.
- **Admission per managed start** (`POST /api/v1/devices/sessions`, idempotent per
  device+session+attempt; fresh attemptId per attempt). New managed starts are **fail
  closed**: unreachable or denied admission blocks the start. There is no cached-approval
  fallback in v1 (deliberate policy choice, flagged for product confirmation). Unenrolled
  tablets are **unmanaged**: legacy V1 behavior, no management calls at all, clearly
  distinguished on the staff screen.
- **Offline queue** (`EventQueueStore`): bounded persisted JSONL (500 events / 256 KiB, oldest
  dropped), flushed via `POST /api/v1/events/batch` (schemaVersion 1, max 100 events / 64 KiB
  per envelope) after heartbeats and admissions. Service-blocked installations send only
  `policy.ack` and `diagnostic.snapshot`; session history waits for unblock.
- **Credential revocation** (`CREDENTIAL_REVOKED` from any route): stops management
  immediately - no further heartbeats, admissions or flushes - and marks the device revoked
  until re-enrollment.

## Emission rules (kept in sync with SessionReporter)

- `session.started` once per logical session, only after admission (or unmanaged start).
- `session.connected` once per attempt that reaches listening; `firstAudioMs` is the
  allowlisted `stream_start_to_first_output_event_ms` sample when measured, else null.
- `connection.failed` once per failed attempt with a contract error code
  (`CONNECTION_TIMEOUT`, `MIC_PERMISSION_DENIED`, `PROVIDER_UNAVAILABLE`,
  `AUDIO_STARTUP_TIMEOUT`, `POLICY_SYNC_FAILED`).
- `session.ended` exactly once per started session with observed wall duration.
- Policy-blocked starts emit only `connection.failed` (`POLICY_SYNC_FAILED` when policy could
  not be synced; nothing when the server itself denied) - no fabricated session.
- Telemetry never carries audio, captions, transcripts, prompts, guest identities, room
  numbers, free-form messages, provider bodies or keys.
- Heartbeats are the only periodic emission; there is no periodic queue flush timer in
  background. Offline backlog uploads on the next heartbeat or admission.

## Update feed audit (agent task requirement)

`UpdateFeed` defaults in `build.gradle.kts` still point at the legacy
`somdipto/manzanilla` repository. This lane does NOT change them: compatibility of the new
`manzanillareception/manzanilla` release-feed path is unproven (redirect/update-path tests
not run; the admin repo `.github` workflows and release policy are shared-ownership files
outside this lane). Changing the feed before a validated redirect test would risk stranding
deployed tablets. Recommendation: backend/org owner validates the GitHub redirect from the
old repo path (or ships a redirecting legacy release), then a follow-up lane flips
`UPDATE_FEED_OWNER`/`UPDATE_FEED_REPO` defaults. Tracked as a coordination item with main.

## Local verification

`./gradlew :hotel:testDebugUnitTest` covers the pure management logic
(`ManagementCredentialsTest`, `ManagementClientTest` with a fake transport,
`EventQueueStoreTest`, `BackoffTest`, `SessionReporterTest`, `ManagementTelemetryTest`,
`AdmissionPolicyTest`) plus the existing suites. No paid or live provider calls are made by
any test. Physical-tablet behavior (heartbeat timing on device, enrollment UX, policy stop
during a live session) is NOT validated by this lane and is called out in the PR.
