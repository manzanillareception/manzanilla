# Manzanilla V2 management portal

An independent React/TypeScript portal for the versioned FastAPI control plane.
It does not replace the Android reception interface or its voice transports.

```sh
cd admin-v2
npm ci
npm test
npm run build
```

`npm run build` type-checks and produces static files in `dist/`. Caddy serves
these files and proxies `/api/v2` to FastAPI on the same origin. No Supabase
secret, provider key or browser access token is compiled into the portal.

For local work, run the isolated backend on port 8080 and `npm run dev`.
The Vite server proxies only `/api/v2` and `/health` to loopback. Configure the
backend's `MANZ_PUBLIC_ORIGIN` to the actual browser origin, normally
`http://127.0.0.1:5173`, and use the explicit development cookie setting for HTTP.
Production uses HTTPS and secure cookies. Do not point local tests at a real hotel.

Authentication uses the server's opaque HttpOnly cookie and a CSRF token held
only in memory. Login/recovery/invitation/MFA requests go to the backend;
the portal never receives reusable Supabase access/refresh credentials.
Configure managed invitation/recovery email links with `token_hash` and
`type=invite` or `type=recovery` in the portal URL. The portal immediately
removes those query values from browser history before verification. Owner
accounts are held at the authenticator screen until the server confirms MFA.

Views include authorized hotels/team membership, code-only device approval,
fleet health, device details and commands, structured public hotel settings,
alerts, audit history and owner-only account enable/disable controls. Server
authorization is authoritative; hiding an action is not an access-control policy.
Fleet reads follow server pagination and distinguish unavailable data from an
empty fleet. The overview reports device observations, not voice-quality scores.

Component tests use explicitly synthetic HTTP fixtures. They cover session and
CSRF handling, email-link cleanup, owner MFA, viewer restrictions, failed reads,
pagination, code pairing and public configuration revisions. They do not prove
real SMTP delivery, Supabase account configuration or physical device acoustics.
