# Candidate branch caveats (must read before any release)

1. Rate limiting: the page watchdog posts at most ~2 diag events per user turn by CLIENT
   discipline only. POST /api/diag-append has NO server-enforced rate limit - it is k-gated
   and session-bound (vc.active.request_id match), but an active-session holder could post
   unboundedly into the 60-entry ring, evicting older diag entries. Add a server-side cap
   before any wider release.
2. NOREPLY_TIMEOUT_MS=5000 and TEXT_GRACE_MS=2000 are hypotheses from healthy samples
   (0.7-2.4s observed), not validated product values.
3. text_only_provisional is provisional: it does NOT prove unsent TTS or empty generation;
   hasText=false means only "no observed text/audio in window".
4. Detection only: no user-visible behavior, no retry/repair action is taken on fire.
5. The legacy /api/session and /api/session/end routes are retired with HTTP 410.
   Existing clients must use a supported configured interface; no replacement is selected.
   The old BudgetDO storage is retained without exposing its retired mint/reset paths.
6. This branch does NOT repair the Spanish recognition failure. Effective recognizer and
   language bounding remain unverified pending provider answer.
