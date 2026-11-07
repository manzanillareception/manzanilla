# V2 deployment and recovery

Status: prepared source on `test-branch`. No VM, cloud project, email recipient,
production data or APK release was changed. Qualify staging before an authorized
one-hotel pilot.

## Hosting and inputs

| Component | Responsibility |
| --- | --- |
| Qualified Linux VM | Caddy/static admin portal, FastAPI and small maintenance worker |
| Managed Supabase | Human Auth and authoritative PostgreSQL; separate staging/production projects |
| Android | Reception, direct OpenAI/Cartesia voice and private local shift memory |

The VM needs no inference GPU. Verify its actual region, ownership, capacity,
disk, patching, firewall and other workloads. Run `scripts/control_vm_check.py`
on the host; it cannot establish residency or production approval. Compose uses
ports80/443 and loopback8080. Use an isolated staging host or reviewed shared
ingress. A single VM remains a single point of failure.

Required: owned DNS/HTTPS domain, approved images, Docker/Compose, managed
database/Auth projects and CA-verified connections, production SMTP, backups,
first-owner subject, MFA recovery, physical Android upgrade acceptance, and
hotel/vendor privacy agreements. EU hosting alone does not prove compliance.

## Protected configuration

Copy `infra/v2/app.env.example` and `migration.env.example` into owner-only regular
files outside the checkout. The application file holds runtime settings; the
migration file holds only the separate DDL-capable database identity. Filled
files never belong in Git, chat, build arguments, images or workflow artifacts.

Hosted staging and production use production settings, the exact HTTPS public
origin, secure cookies and separate stable Fernet session keys. Changing a key
invalidates sessions; plan reauthentication. Use the real direct Supabase
endpoint on IPv6-capable hosts or the session pooler for IPv4. SQLAlchemy URLs
use `postgresql+psycopg`, URL-encoded credentials and `sslmode=verify-full` with
a working CA. Do not disable TLS validation.

Runtime and migration URLs must identify the same database/project through the
same endpoint, with different roles. The helper also checks shared-pooler project
suffixes. Role names alone do not establish actual permissions.

## First database and owner setup

Using the reviewed control image and database-only migration file, run Alembic
before activating the runtime login. With the approved image references/domain
configured in the operator shell:

```sh
MANZ_ENV_FILE=/approved/private/path/migration.env \
  docker compose --file infra/v2/compose.yml run --rm --no-deps api \
  alembic -c control/alembic.ini upgrade head
```

This starts neither API nor worker. An approved database administrator then
applies `infra/v2/runtime-role.sql` to the dedicated control database. Set the
runtime password and enable its login through the approved administrative
interface, storing credentials only in protected configuration. The SQL itself
generates no password and creates an inactive login.

The `manzanilla_control_api` role has no ownership, administrative privileges or
RLS bypass. Explicit role policies grant control-table CRUD and migration-version
SELECT. Public DDL and browser/Data API reads are denied. Setup rejects unsafe
pre-existing privileges/memberships/ownership and removes broad public grants;
use a dedicated project and review existing grants first. Runtime requests still
require server-enforced hotel/device authorization. Reapply/review grants when
migrations add tables. Keep the DDL identity separate from normal services.

Configure managed Auth's site URL, allowlisted redirects and production SMTP.
Invite/recovery templates use the portal origin with `token_hash` and
`type=invite` or `type=recovery`. The portal removes these values from history;
configure upstream access logs to scrub them as well. This ingress does not
enable ordinary request access logging.

Create the first verified Auth identity through the approved administrative
flow. Set its exact subject in `MANZ_BOOTSTRAP_OWNER_SUB`, sign in and complete
MFA. Remove bootstrap configuration after the owner is saved. Managers receive
invitations and choose passwords. Guests have no account. Device pairing and
manager browser sessions are independent; native setup requires Android's lock.

## Verification and deployment

Commands/evidence: [v2-implementation.md](v2-implementation.md).
The new CI runs disposable PostgreSQL16 migration/drift/permission/concurrency
tests, portal tests/build and real browser/API fixtures. Tests contain synthetic
identities/data. SQLite cannot qualify PostgreSQL locking. Local container
execution was unavailable; check Docker/Caddy results in CI.

The manual candidate workflow builds key-free review images and a source bundle.
It does not deploy or publish registry images/APKs. Review immutable image digests
and required checks before promotion. Inspect the deployment helper's help and
generated plan:

```sh
python scripts/control_deploy.py validate \
  --env-file /approved/private/path/control.env \
  --migration-env-file /approved/private/path/migration.env
python scripts/control_deploy.py --help
```

`plan` accepts reviewed image references and domain and is read-only. Actual
`deploy` requires the intended environment and matching confirmation. This
development task does not authorize deployment. Staging and production require
distinct credentials/projects/data.

The helper pulls approved images, migrates in a temporary container receiving
only migration credentials, starts normal services with runtime credentials,
and probes loopback readiness and the public HTTPS portal. Migration failure
stops deployment. A source push is not an APK update.

The API trusts proxy headers only behind private/loopback Caddy. Caddy replaces
client-IP/scheme headers. Never expose8080 publicly or add untrusted clients to
that boundary. A CDN needs a tested trusted-proxy chain and token-safe logs.

After approval, test owner login, hotel creation, actual-device pairing and fresh
reporting in staging. Check second-hotel denial, reconnect/lost replies, command
expiry/replay, busy deferral, receipts and Android installation confirmation.
Real email recipients/provider tests need applicable authorization/budget. Pilot
one hotel and qualify recovery before increasing cohorts.

## Monitoring and recovery

Monitor readiness/errors, database saturation, worker progress, command expiry,
container restarts, disk and backups. Worker health checks persisted successful
progress; a restart policy alone does not recover an unhealthy running process.

Telemetry contains minimized numeric/boolean metadata, not guest audio, captions,
private notes or provider keys. The worker prunes diagnostics after fourteen
days in bounded batches. Define audit/operational retention, deletion authority,
backup expiration, actual processing locations and support access before launch.

Restore backups into isolated staging and verify hotel scope, memberships,
credentials/revocations, command receipts and config versions before reopening
access. Set recovery objectives from real drills and the selected backup plan.
Database backups do not include object bytes or local device memory.

Rollback uses a reviewed schema-compatible prior image, explicit environment
confirmation and `--confirm-compatible-schema`. It does not downgrade the
database. Reconcile uncertain effects and revocations after recovery. Android
OTA/signing, older bundled provider credentials and cloud memory synchronization
remain separate reviewed changes.
