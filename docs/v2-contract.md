# V2 implementation contract

Development branch: `test-branch`. This is an isolated control plane; the working
V1 voice routes, native keys, guest interface, package and signing identity stay intact.

## Runtime

`control.app:create_app(settings=None, identity_provider=None)` creates the FastAPI
app; `control.app:app` is the production entry point. `/api/v2` is the only product
API prefix. The production database is PostgreSQL, with versioned Alembic
migrations. SQLite is allowed only for isolated development/unit fixtures.

Shared Python modules: `control.db` exports `Base` (SQLAlchemy DeclarativeBase),
`make_engine(url)` and `make_session_factory(engine)`; `control.models` exports
`User` with `id` (Supabase subject string), `email`, `enabled`, `platform_owner`.
Other tables are owned by the backend implementation. Authentication owns its
session tables and registers them in the same Base metadata.

`control.settings.Settings` exposes `database_url`, `public_origin`, `environment`,
`supabase_url`, `supabase_publishable_key`, `supabase_secret_key`,
`session_encryption_key`, `bootstrap_owner_sub`, `session_ttl_seconds` and
`cookie_secure`. Environment names are `MANZ_` + uppercased field name.

`control.auth.AuthService(settings, session_factory, identity_provider=None)`
provides `require_user(request)` returning immutable `Principal(id, email,
platform_owner)`, `invite(email, redirect_to)` returning a managed subject ID,
and `router` for the auth routes. Authorization must consult current User and
membership records. User sessions use opaque HttpOnly cookies and CSRF; no
privileged Supabase key reaches the browser/device.

## Browser API

Browser list responses contain `items`, `total`, and `next_offset` (null at the
end); native command polling contains `items`. Browser lists accept bounded
`limit` and `offset`. Errors
are JSON with a safe `detail` string/object and appropriate HTTP status. Mutations
require JSON, same-origin checks and `X-CSRF-Token` for authenticated cookies.

- `POST /auth/login {email,password}`; `GET /auth/session` returns
  `{user:{id,email,platform_owner},csrf_token,requires_password,requires_mfa}`;
  `POST /auth/logout`. Restricted invitation/recovery and pending-MFA sessions
  cannot read fleet data.
- `POST /auth/recover {email}` and `POST /auth/verify {token_hash,type}` for
  invite/recovery email links; `POST /auth/password {password}` with a verified
  session. No public account registration or client-supplied role grants.
- Owner sign-in requires managed TOTP MFA. `GET /auth/mfa/factors` returns
  `{items}`; `POST /auth/mfa/enroll {}` returns the temporary setup;
  `POST /auth/mfa/challenge {factor_id}` returns `{id,expires_at}`;
  `POST /auth/mfa/verify {factor_id,challenge_id,code}` returns the updated session.
  Challenges are session-bound, expiring and one-use; enrolled verified factors
  must be verified before adding a replacement.
- Owner only: `GET /users`; `PATCH /users/{user_id} {enabled}`. Suspension
  invalidates existing local sessions; self-disable and owner lockout are denied.
- `GET/POST /hotels`; hotel create body `{name,timezone}`.
- `GET /hotels/{hotel_id}`; `GET /hotels/{hotel_id}/members`;
  `POST /hotels/{hotel_id}/invitations {email,role}` where roles are
  `manager`, `operator`, `viewer`; `PATCH /hotels/{hotel_id}/members/{user_id}
  {role}` and `DELETE /hotels/{hotel_id}/members/{user_id}`. Existing identities
  added to a second hotel are revalidated against managed Auth, not linked using
  a stale cached email.
- `GET /devices` (optional `hotel_id` filter); `GET /devices/{device_id}`;
  `POST /devices/{device_id}/revoke`.
  Detail includes `telemetry_summary`: fourteen-day counts of received session
  start/end and audio-error reports, plus timing means/min/max/sample counts over
  at most the latest 1,000 received `voice_timing` events. Response discloses
  received-time window, total/sample counts and truncation. These are reported
  observations, not complete guest usage or acoustic latency measurements.
- `GET /enrollments`; `POST /enrollments/approve {hotel_id,user_code}` is the
  portal's code-only approval flow. The request-specific variant
  `POST /enrollments/{request_id}/approve {hotel_id,user_code?}` is also available.
- `GET /hotels/{hotel_id}/config`; `PUT /hotels/{hotel_id}/config
  {expected_version,config}`. Config is approved public hotel information, not keys.
  Supported fields are `name`, `address`, `area`, `opening_hours`, `facts`,
  paired `latitude`/`longitude`, reviewed `local_pack`, `availability`, and
  `availability_updated_at_ms`. Explicit null coordinates clear a prior location.
  Translator language choices remain local. A cloud revision is applied only
  while native audio is idle and acknowledged after durable saving.
- `GET/POST /devices/{device_id}/commands`; creation body
  `{type,arguments,idempotency_key}`. Allowlist: `refresh_config`, `check_update`,
  `diagnostics`. No arbitrary URLs/shell/remote recording.
- `GET /alerts`; `POST /alerts/{id}/acknowledge`; `GET /audit`.

## Native device API

Paths below also use the `/api/v2` prefix. Enrollment poll capability and device
credentials are high-entropy secrets, sent only over HTTPS (loopback fixture
exception). Native fleet identity is stored separately from V1 HotelConnection.

- `POST /enrollment/start {installation_id,device_name,poll_token?}` returns
  `{request_id,user_code,poll_token,expires_at}`. Requires local staff setup;
  server rate-limits unauthenticated issuance. Native persists its own random
  `poll_token` before the first request so losing that response can recover the
  same request; mismatched installation/name cannot reuse the capability.
- `POST /enrollment/{request_id}/poll {poll_token}` returns `{status}` or
  `{status:"approved",device_id,device_token,hotel_id}` until activation/expiry.
  Credential replay/recovery is installation-capability-bound and encrypted at rest.
- `POST /device/activate` with Bearer device token confirms native durable save.
- `POST /device/credentials/rotate {idempotency_key}` returns a staged replacement
  credential. Native persists the operation before requesting it, saves the
  replacement before activation, and retains the old encrypted credential until
  activation is acknowledged. Only activation revokes the previous credential.
- `POST /device/heartbeat` with Bearer token and
  `{schema_version:1,boot_id,boot_generation,sequence,app_version,version_code,foreground,
  mode,mic_state,config_version,update_state,health}` returns
  `{device_id,server_time,desired_config_version}`. `health` accepts only
  allowlisted scalar metadata; unknown/private fields are rejected.
  `boot_generation` is a monotonically increasing installation counter persisted
  before the first heartbeat in each reporting epoch. Old epochs and replayed
  sequences cannot replace current presence, including an unseen delayed boot.
- `POST /device/events {events:[{id,type,occurred_at,metrics}]}` is bounded,
  idempotent and cannot refresh current presence.
- `GET /device/config` returns `{version,config}`;
  `POST /device/config/ack {version}` only after successful application.
- `GET /device/commands` returns
  `{items:[{id,type,arguments,created_at,expires_at,state,result_code}]}`;
  `POST /device/commands/{id}/receipt {state,result_code}` supports received,
  deferred, running, succeeded, failed, reconciliation_required. Expired/revoked
  commands cannot execute. A receipt is not proof of install unless verified.
  Native durably claims an operation and obtains the running acknowledgment
  before any effect. Lost acknowledgments or process death lead to reconciliation
  rather than repeating an uncertain effect. Completed local receipts are replayed.

The telemetry collector never reports audio, captions, names, room numbers,
private memory, API keys or tool arguments/results. Current presence derives
from fresh accepted heartbeats; historical events cannot turn an old device green.

## Delivery

Each component supplies isolated tests. Root integrates and runs PostgreSQL and
browser checks where available. CI has a PostgreSQL service and no real provider
credentials. Deployment files are prepared, not applied. No main push, merge,
release publication, paid provider call or external invitation is authorized.
