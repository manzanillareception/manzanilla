# Verification and acceptance

## Current remediation acceptance

The authoritative repair IDs R01-R17 and approved 17.0.6 visual requirements are
in [the remediation spec](theme-handoff-contract.md). Passing evidence is recorded
separately in `artifacts/VERIFICATION.md`; this file defines the procedure.

1. **Reproduce before repairing.** For each repair ID, preserve a minimal
   consumer-visible failure scenario. Keep deterministic native/domain
   regressions at the real ownership boundary; use isolated actual-UI scenarios
   for browser/WebView behavior. Avoid source-text, copied-default and mock-echo
   assertions.
2. **Run existing suites after the integrated change.** From the repository root,
   run `python -m pytest tests -q`. From `android`, run
   `bash gradlew :hotel:testDebugUnitTest :hotel:assembleDebug --no-daemon`.
   Build the release candidate with the established signing configuration, then
   verify the actual APK's package, version code, certificate and digest.
   Compilation alone does not close any user-workflow acceptance row.
3. **Exercise the guest surface.** Serve the actual packaged reception assets
   from an isolated fixture with an explicit native bridge. Exercise translation,
   same-language rejection, Spanish assistance, greeting/cancel, both caption
   forms, message review/cancel/save, all shortcut outcomes, late events,
   language-preserving host refresh and update offer/progress/action. Inspect
   visible audio state, guest-left/receptionist-right paired caption fixtures,
   explicitly unlinked provider captions, both appearances, RTL, text scaling,
   reduced motion and hidden/resumed state. Browser bridge fixtures establish UI
   behavior only; never claim they prove real voice pairing.
4. **Exercise Android ownership and updates.** Use an isolated landscape AVD with
   synthetic configuration, then the target physical device for acoustic checks.
   Cover permissions, idle timeout, pause/resume, screen sleep, disconnect,
   rapid start/stop and recreation. Install the candidate over isolated copies
   of the previous release and 17.0.4, retaining saved configuration/data and
   signing identity. Advance the release feed during a resumed download and
   verify that stale bytes cannot be offered as the new release.
5. **Exercise operational boundaries.** Use a disposable backend/property and
   existing gateway fixtures. Verify authentication, cross-hotel denial, scoped
   booking reads, explicit write confirmation, duplicate call IDs, durable
   requests and accurate receipts. Then exercise the configured real PMS,
   operator and taxi routes in approved test environments; synthetic receipts
   do not prove actual external delivery.
6. **Measure voice acceptance.** Follow issue #2's A/B protocol using the same
   audio/network/device conditions, at least 100 synthetic hotel utterances,
   repeated trials, both directions, interruptions, names/numbers/dates/currencies
   and bilingual human review. Report end-of-speech to first audible output,
   p50/p95, failures, entity denominators, echo, barge-in and costs. Targets:
   Day p50 <1.2 s/p95 <2 s; Night p50 <1.5 s/p95 <2.5 s; interruption <150 ms
   where hardware supports it; >=95% successful conversations; >=99.5% critical
   entity preservation; <1% self-echo turns; <2% failed/retried turns.
   A configured interruptible flag or 52-language menu is not passing evidence.
7. **Keep spending and evidence honest.** Aggregate live calls must remain within
   the authorized $4 maximum. Check verified entitlement/pricing and reserve a
   conservative cost bound before calls; stop when safe remaining spend cannot
   be established. Full real-provider language/one-hour Day/two-hour Night soak
   acceptance stays open if that allowance cannot fund it. Fake-provider resource
   soak proves local ownership/bounds, not acoustic or real-provider quality.
8. **Separate diagnostic publication from production acceptance.** Record commit,
   artifact identity, scenario, result, device evidence, benchmark coverage and
   remaining limits. Production acceptance requires all R01-R17 rows and applicable
   hotel gates. An explicitly authorized testing APK may be published with its
   unverified hardware/quality/integration gates disclosed; it is not production
   acceptance. Failed or unavailable gates are never omitted or marked passed.

No live app/data mutations are permitted for these checks. Use a disposable
landscape AVD and isolated backend databases; do not substitute browser screenshots
for native Android installation or microphone/audio evidence.

## Historical backend baseline

The following records the original backend/handoff verification scope. Its
OpenAI dependency and statements about unavailable signing/releases are historical,
not the selected Fish/Gemini architecture or current 17.0.4 release evidence.

Original handoff SHA-256 verification passes for 751 listed files plus manifest. Application tests cover persistent notes/deduplication, confirmation, booking verification/session scoping, prohibited tools, taxi queued/not-booked semantics, escalation failure, PMS-unavailable fallback, knowledge, tenant roles, revocation, inbox isolation, morning window, one-use WebSocket tickets and missing API key failure. Realtime integration test executes actual relay code against a fake upstream: PCM input, tool arguments, device confirmation, durable note, function output, PCM output and interruption/truncation. This is not real model/device/language acceptance. Edge tests verify fixed-file lookup and capability/path denial. Admin JS syntax and Python compilation pass. Android hotel debug APK builds under JDK 17/SDK 34/Gradle 8.5.

Run `python -m pytest tests -q` from project root. README records the final count. One dependency AnyIO deprecation warning is emitted. The Edge transport test launches actual cloud and Edge subprocesses over trusted local TLS, verifies lookup and disconnect fallback. Android lint has no errors; retained WebView constructor/accessibility warnings remain. No Docker/CI remote deployment, signed production release, Windows service, emulator or physical phone test has been performed.

| Real pilot test | Required proof |
|---|---|
| Multilingual conversation | Understandable spoken reply for each intended language; guest understands confirmation UI. |
| Taxi | Correct confirmed destination/time; staff handles inbox request or actual provider books it. No false booking claim. |
| Morning note | Note visible after phone/backend restart, with guest/room/time/session and summary; staff resolves it. |
| Check-in | Real PMS verified reservation/eligibility; approved instructions; no autonomous door/payment/modification. |
| Human request | Immediate approved transfer to primary, answered/no-answer detection, backup tested. Dialer opening alone fails this acceptance gate. |
| Concierge | Answers match actual hotel data; unknown questions escalate. |
| PC unavailable | Voice/knowledge/cloud notes still work; Edge-only lookup degrades safely. |
| Phone restart | Selected HOME launches, stable ID/token survive, heartbeat/config recover. |
| Internet/OpenAI failure | Mic/audio close and truthful fallback; retry creates one session. |
| PMS/CRM failure | Lookup error, durable cloud note never dropped. |
| Edge reboot | Automatic startup/reconnect, safe timeout; no shell or arbitrary file access. |
| Interruption | Old playback clears; reported played position agrees with handset audio. |
| Stop during connect | No delayed microphone/socket resurrection. |
| Duplicate/malformed tools | One note per call ID, conflicts rejected, unknown fields denied. |
| Tenant isolation | Staff/device from Hotel A cannot access or alter Hotel B. |
| Update/rollback | Same signed APK for four hotels; config updates without APK; recovery build with higher versionCode. |

Blocked on actual four-hotel PMS/CRM vendor data and credentials, OpenAI account/key, handset mic compatibility, SIM/SIP/native telephony provider, operator/taxi workflows, signing key, deployment host and PC service QA. Obtain those before claiming all six features work end-to-end. Signed APK, managed-device lockdown, retention/backup and per-hotel acceptance are release gates.

## Voice repair reproduction (unreleased)

Keep the remediation gates above. Never run these fixtures against the installed hotel app,
guest data, or real provider credentials. The changes do not establish acoustic latency.

The 17.0.21 candidate adds case selection to the native fixture
(`-e case <failed-start|failed-start-uncertain|predispatch-recovery|predispatch-storage-failure|dispatched-recovery|settled-storage-retry|managed-rejection|pending-end>`,
plus `-e status/-e late/-e notCreated` for the HTTP cases) on a clean isolated AVD. Backend behavior
coverage now includes actual `POST /v1/agent/sessions` rejections carrying the
`X-Manzanilla-Session-Creation: not-created` receipt, durable recovery without a provider lookup,
pre-creation configuration failures, ambiguous/lost responses staying blocked, and unchanged reserved
duration. Renderer coverage adds the visible microphone-status label. None of this measures speech.

- Native/JVM: from `android`, run `./gradlew :hotel:testDebugUnitTest :hotel:assembleDebug :hotel:assembleDebugAndroidTest :hotel:lintDebug --no-daemon --max-workers=2` with JDK 17, the Android SDK, and the **non-secret fixture** `FISH_VOICE_ID` environment override. Builds never embed a Fish key, so no `FISH_API_KEY` override exists; provider access in manual testing comes from a device-entered key. Add `-PfeedUrl=http://127.0.0.1:9/disabled` only to the isolated fixture build; it enables cleartext local HTTP. Do not distribute that debug APK.
- Fresh disposable AVD only: install `hotel/build/outputs/apk/debug/hotel-debug.apk` and `hotel/build/outputs/apk/androidTest/debug/hotel-debug-androidTest.apk`; run `adb -s <isolated-serial> shell am instrument -w -r [-e case ...] dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture`. Require `INSTRUMENTATION_CODE: -1` and its PASS output, not merely shell exit 0. The fixture runs actual `FishVoice.stop` against a pending loopback end request and a real delayed creation POST, repeated stop, failed/non-terminal end responses, timeout, stale producers, failed-start acknowledgement, restored predispatch/dispatched recovery, settled-storage retry and managed rejection/header boundaries. It does **not** open a microphone or join a provider room; actual capture/playback release, hardware routing and physical background/restart behavior remain device gates.
- `-e case reception-bridge` loads the packaged HTML in the actual native WebView, checks queued capability/language events, toggles appearance and clicks Start across the real JavaScript bridge. Its action callback is isolated from providers.
- `-e case microphone-permission`, `microphone-permission-denied` and `microphone-permission-background` click the actual reception Start control and interact with the system permission dialog. Run only on a fresh disposable AVD with a key-free APK and no saved Fish/hotel configuration. Reset the disposable app with `adb shell pm clear dev.orange.hotel` before each case so permission can be requested afresh. Select the AVD's normal launcher as default before the background case; a floating launcher chooser does not actually background the app. Grant must preserve native and JavaScript session ownership, resume once and show the missing-credential error. Denial must show its recoverable error; backgrounding must cancel without starting. These cases never connect to a provider or open capture.
- Browser: serve the repository root on loopback (`python3 -m http.server 8765 --bind 127.0.0.1`), open that origin in an isolated browser, then import `/tests/reception_caption_fixture.js` and call `runReceptionCaptionFixture()`. Its iframe installs an explicit fake native bridge before loading the real shipped renderer. It checks model-reported language placement, repeat/unclear-language continuity, eight language orientations and physical positions, exact text/escaping, late metadata/finality, unchanged nodes, microphone/activity updates, scrollback/follow, full-render index rebuilding, reset and stale sessions. Run `node --test tests/reception_language_turns.test.cjs` for all 2,652 ordered picker pairs and the reducer edge cases. No microphone or paid provider call is made.
- Backend: `.venv/bin/python -m pytest tests -q` uses disposable databases and provider fixtures. Request recovery is `POST /api/device/fish-session-requests/{request_id}/end`, device-authenticated and fenced to the immutable device/hotel/request reservation. The native mint sends `request_id`; direct Fish creation uses a unique per-request `end_user_id`. A stale end cannot terminate a newer request. Received documented non-creating rejections settle their exact lease; empty provider lookup, unknown creation, non-terminal acknowledgement, changed credentials, or failed storage remain blocked; never clear the reservation by guessed expiry.
- Offline detector: `python3 tools/voice_latency.py calibrate /tmp/manzanilla-cal.wav --offset .4 --report /tmp/manzanilla-cal.json`, then `python3 tools/voice_latency.py analyze /tmp/manzanilla-cal.wav --input-window 0 1.6 --output-window 0 1.6 --input-noise-window 0 .1 --output-noise-window 0 .1 --calibration /tmp/manzanilla-cal.json`. Repeat with `--offset -.1` to check overlap. These are known-offset **non-speech** signals, not translations.

For real evaluation, use fixed synthetic speech and an isolated matching tablet. One calibrated
stereo recorder must capture the played input reference and the tablet speaker on a common clock.
Inspect leakage/noise/physical delay and listen to detected boundaries. Analyze startup, greeting,
warmed turns, long utterances, interruptions and recovery separately; record first correct audible
speech and complete-translation end. Monotonic `AudioTiming` markers and allowlisted WebRTC receipt
statistics are diagnostic only; decoded and first-non-silent decoded audio are not audible speech.
Do not invent provider ASR/LLM/TTS spans or align independent clocks without calibration.

Retain the Day gates: p50 <1.2s, p95 <2s, >=95% successful conversations, >=99.5% critical-entity
preservation, <1% self-echo, <2% failed/retried turns, and interruption <150ms where hardware supports it.
Require >=100 distinct held-out synthetic utterances with repetitions and both directions for every
evaluated language pair, including pauses/negation/corrections, names/numbers/dates/prices, recovery
and duplicates. Bilingual human meaning/naturalness review and sample-size/uncertainty reporting
remain required. The analyzer retains scheduled failures/timeouts (15s qualification score, not an
acoustic timestamp) and blocks missing captures; successful-only percentiles are conditional diagnostics.
Its descriptive summary does not replace the existing latency plan's confirmed comparison/quality gates.
The historical $4 allowance is aggregate, not renewed: no paid trial without established remaining
authorization and a conservative reservation.



## 17.0.27 reception browser regression runner

Install `requirements-browser.txt` and run `python -m playwright install chromium`,
then `python tests/run_reception_browser.py`. To reuse a system browser, pass
`--chromium /usr/bin/chromium`. CI runs this alongside Python, Node and Android
checks. The runner blocks external requests and exercises the real shipped assets
with isolated bridge events for captions, notes and appearance. Android JVM tests
separately verify that the native asset policy permits each dependency in the
shipped HTML; a permissive HTTP fixture alone cannot catch that APK boundary.

Use [the candidate investigation](realtime-reception-fix.md) for speech-first
caption migration and the physical old/new acceptance comparison. Pending/unknown
note outcomes retain the exact confirmation identity and last user intent; verify
that retries and cancellation return one durable receipt without another write.
