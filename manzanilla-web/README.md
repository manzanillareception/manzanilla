# Manzanilla web - candidate working copy (Nov 2, 2026)

## What this is
The full source of the Manzanilla managed-web test worker (Cloudflare worker: client page + relay in one file), plus the fixes produced while investigating the Nov 1 demo failure.

## Status - read first
- PRODUCTION IS UNCHANGED. The live worker still runs the version deployed before this work (version 4a57ff62). Nothing in this package is deployed.
- THE SPANISH RECOGNITION FAILURE IS NOT FIXED. The Nov 1 call where later user turns were transcribed as Devanagari text remains unresolved; a support question for the speech provider is drafted but not sent.
- This package is a development working copy for review, not a release.

## What's in here
- manzanilla-web/src/index.js - worker source with the investigated fixes (capture-info allowlist, resample wiring, AGC 'unknown' preservation, language-hints guard in night mode, playback source pruning) and the diagnostic no-reply watchdog (clearly marked candidate code between WATCHDOG-BEGIN/END markers).
- manzanilla-web/wrangler.toml - worker config. No secrets in it; API keys live in worker secrets/environment on the deploy side and are NOT in this package.
- manzanilla-web/public/prev-x7q2/v2/ - the reception page assets.
- test-watchdog.mjs - test file for the watchdog (16/16 passing locally).
- CANDIDATE-CAVEATS.md - known limitations of the candidate watchdog code (read before any deployment decision).

## What the changes do
1. Capture-info and playback robustness fixes (reviewed).
2. Night-mode guard for a missing-staff edge case.
3. A diagnostic-only watchdog: it DETECTS when the user spoke but got no reply within a timeout and records that event. It does not fix recognition or translation and changes nothing user-visible by default.

## What was NOT done
- No fix for the wrong-language transcription itself (provider-side question pending).
- No deployment, no release, no changes to the live site.

## Retired session route

The legacy `/api/session` and `/api/session/end` endpoints return HTTP 410 after
access validation. They do not read cached tokens, change stored budgets, or
contact an upstream provider. The corresponding internal mint/state/reset paths
are also retired. The existing Cartesia pilot and separate `src/v2` backend are
unchanged by this removal. No deployed service was modified.
