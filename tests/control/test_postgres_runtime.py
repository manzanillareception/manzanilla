"""Native PostgreSQL checks, isolated from both production and SQLite fixtures.

CI supplies a disposable local PostgreSQL service. A local PGlite run can prove
SQL-dialect behavior, but its single-connection multiplexer cannot qualify real
multi-connection row locks; that test is explicitly skipped there.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import threading
import time
import uuid

import pytest
from fastapi import HTTPException
from sqlalchemy import event, func, inspect, insert, select, text, update
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError, IntegrityError

from control import auth, models, schemas, service  # Registers both model sets in shared metadata.
from control.db import Base, make_engine, make_session_factory
from control.settings import Settings


@pytest.fixture
def pg_url():
    value = os.environ.get("MANZ_TEST_DATABASE_URL")
    if not value:
        pytest.skip("MANZ_TEST_DATABASE_URL requires a disposable local PostgreSQL service")
    url = make_url(value)
    if url.get_backend_name() != "postgresql" or url.host not in {"localhost", "127.0.0.1", "::1"}:
        pytest.fail("PostgreSQL runtime checks require a disposable loopback database")
    return value


@pytest.fixture
def pg_schema(pg_url):
    engine = make_engine(pg_url)
    schema = "control_verify_" + uuid.uuid4().hex
    quoted = engine.dialect.identifier_preparer.quote(schema)
    with engine.begin() as connection:
        connection.execute(text("CREATE SCHEMA " + quoted))
    isolated = engine.execution_options(schema_translate_map={None: schema})
    try:
        Base.metadata.create_all(isolated)
        yield isolated
    finally:
        with engine.begin() as connection:
            connection.execute(text("DROP SCHEMA " + quoted + " CASCADE"))
        engine.dispose()


def seed_hotel(engine):
    with engine.begin() as connection:
        connection.execute(insert(models.User), {
            "id": "fixture-owner", "email": "owner@example.invalid", "enabled": True,
            "platform_owner": True,
        })
        connection.execute(insert(models.Organization), {
            "id": "fixture-org", "name": "Synthetic organization",
        })
        connection.execute(insert(models.Hotel), {
            "id": "fixture-hotel", "organization_id": "fixture-org", "name": "Hotel 日本語",
            "timezone": "Europe/Madrid", "config_version": 0,
        })


def test_postgres_json_unicode_foreign_keys_and_rollback(pg_schema):
    seed_hotel(pg_schema)
    with pg_schema.begin() as connection:
        connection.execute(insert(models.Device), {
            "id": "fixture-device", "hotel_id": "fixture-hotel",
            "installation_id": "fixture-installation", "name": "Desk tablet", "status": "active",
            "health": {"microphone_permission": True, "battery_percent": 80},
        })
    with pg_schema.connect() as connection:
        assert connection.scalar(select(models.Hotel.name)) == "Hotel 日本語"
        assert connection.scalar(select(models.Device.health)) == {
            "microphone_permission": True, "battery_percent": 80,
        }
    with pytest.raises(IntegrityError):
        with pg_schema.begin() as connection:
            connection.execute(insert(models.Membership), {
                "user_id": "missing-user", "hotel_id": "fixture-hotel", "role": "viewer",
            })
    with pytest.raises(RuntimeError, match="injected transaction failure"):
        with pg_schema.begin() as connection:
            connection.execute(insert(models.AuditEvent), {
                "id": "must-rollback", "hotel_id": "fixture-hotel", "actor_id": "fixture-owner",
                "action": "fixture_action", "target_id": "fixture-device",
            })
            raise RuntimeError("injected transaction failure")
    with pg_schema.connect() as connection:
        assert connection.scalar(select(func.count()).select_from(models.AuditEvent)) == 0


def test_postgres_idempotency_and_installation_uniqueness(pg_schema):
    seed_hotel(pg_schema)
    with pg_schema.begin() as connection:
        connection.execute(insert(models.Device), {
            "id": "fixture-device", "hotel_id": "fixture-hotel", "installation_id": "same-installation",
            "name": "Desk tablet", "status": "active",
        })
        connection.execute(insert(models.Command), {
            "id": "fixture-command", "device_id": "fixture-device", "hotel_id": "fixture-hotel",
            "creator_id": "fixture-owner", "type": "diagnostics", "arguments": {},
            "idempotency_key": "same-operation", "fingerprint": "fixture-fingerprint",
            "expires_at": 4_000_000_000.0,
        })
    with pytest.raises(IntegrityError):
        with pg_schema.begin() as connection:
            connection.execute(insert(models.Device), {
                "id": "another-device", "hotel_id": "fixture-hotel", "installation_id": "same-installation",
                "name": "Duplicate", "status": "active",
            })
    with pytest.raises(IntegrityError):
        with pg_schema.begin() as connection:
            connection.execute(insert(models.Command), {
                "id": "duplicate-command", "device_id": "fixture-device", "hotel_id": "fixture-hotel",
                "creator_id": "fixture-owner", "type": "diagnostics", "arguments": {},
                "idempotency_key": "same-operation", "fingerprint": "fixture-fingerprint",
                "expires_at": 4_000_000_000.0,
            })
    with pg_schema.connect() as connection:
        assert connection.scalar(select(func.count()).select_from(models.Device)) == 1
        assert connection.scalar(select(func.count()).select_from(models.Command)) == 1


def test_postgres_device_lifecycle_and_accepted_storage_boundaries(pg_schema):
    """Exercise real service transactions, including bounds SQLite cannot enforce."""
    seed_hotel(pg_schema)
    sessions = make_session_factory(pg_schema)
    settings = Settings(environment="test")
    actor = auth.Principal("fixture-owner", "owner@example.invalid", True)
    with sessions.begin() as db:
        pairing = service.start_enrollment(db, schemas.PairStart(
            installation_id="fixture-installation-1234", device_name="Synthetic desk"), "127.0.0.1")
    with sessions.begin() as db:
        service.approve_enrollment(db, settings, actor, pairing["request_id"],
                                  schemas.PairApprove(hotel_id="fixture-hotel"))
    with sessions.begin() as db:
        issued = service.poll_enrollment(db, settings, pairing["request_id"],
                                         pairing["poll_token"], "127.0.0.1")
    authorization = "Bearer " + issued["device_token"]
    with sessions.begin() as db:
        assert service.activate(db, authorization)["status"] == "active"
    with sessions.begin() as db:
        service.heartbeat(db, authorization, schemas.Heartbeat(
            schema_version=1, boot_id="fixture-boot", boot_generation=1, sequence=2**53 - 1,
            app_version="17.0.45", version_code=75, foreground=True, mode="idle",
            mic_state="off", config_version=0, update_state="current", health={
                "microphone_permission": True, "battery_percent": 100,
            }))
        service.change_config(db, actor, "fixture-hotel", schemas.ConfigChange(
            expected_version=0, config=schemas.PublicConfig(name="Hotel 日本語")))
    with sessions.begin() as db:
        assert service.ack_config(db, authorization, schemas.ConfigAck(version=1))["version"] == 1
        command = service.create_command(db, actor, issued["device_id"], schemas.CommandCreate(
            type="diagnostics", idempotency_key="fixture-command-lifecycle"))
    for state in ("received", "running", "succeeded"):
        with sessions.begin() as db:
            # This 64-character public schema boundary used to exceed VARCHAR(60).
            result = service.command_receipt(db, authorization, command["id"],
                schemas.CommandResult(state=state, result_code="a" * 64))
            assert result["state"] == state
    with sessions() as db:
        device = db.get(models.Device, issued["device_id"])
        assert device.boot_generation == 1
        assert device.sequence == 2**53 - 1
        assert device.config_version == 1
        assert service.get_config(db, db.get(models.Hotel, "fixture-hotel"))["config"]["name"] == "Hotel 日本語"
        assert db.scalar(select(models.Command.result_code)) == "a" * 64
        assert db.scalar(select(func.count()).select_from(models.CommandReceipt)) == 3


def test_native_postgres_row_lock_blocks_a_second_writer(pg_schema):
    with pg_schema.connect() as connection:
        version = connection.scalar(text("SELECT version()"))
    if "PGlite" in version:
        pytest.skip("PGlite multiplexes one backend connection; native PostgreSQL CI qualifies row locking")
    seed_hotel(pg_schema)

    def competing_writer():
        try:
            with pg_schema.begin() as connection:
                connection.execute(text("SET LOCAL lock_timeout = '250ms'"))
                connection.execute(select(models.Hotel).where(models.Hotel.id == "fixture-hotel").with_for_update())
        except DBAPIError as exc:
            return getattr(exc.orig, "sqlstate", None)
        return None

    with pg_schema.begin() as first:
        first.execute(select(models.Hotel).where(models.Hotel.id == "fixture-hotel").with_for_update())
        with ThreadPoolExecutor(max_workers=1) as executor:
            assert executor.submit(competing_writer).result(timeout=5) == "55P03"
        first.execute(update(models.Hotel).where(models.Hotel.id == "fixture-hotel").values(config_version=1))
    with pg_schema.connect() as connection:
        assert connection.scalar(select(models.Hotel.config_version)) == 1


def test_native_postgres_revocation_and_device_auth_use_one_lock_order(pg_schema):
    """A revoker holding the device row must still acquire its credential row.

    The heartbeat deliberately reaches its device-row lock while revocation is
    in progress. Credential-first authentication would hold the opposite lock
    and make this deterministic revocation attempt time out or deadlock.
    """
    with pg_schema.connect() as connection:
        version = connection.scalar(text("SELECT version()"))
    if "PGlite" in version:
        pytest.skip("Native PostgreSQL CI is required to qualify concurrent revocation locking")
    seed_hotel(pg_schema)
    token = "fixture-token-" + "x" * 48
    with pg_schema.begin() as connection:
        connection.execute(insert(models.Device), {
            "id": "fixture-device", "hotel_id": "fixture-hotel", "installation_id": "fixture-installation",
            "name": "Desk tablet", "status": "active",
        })
        connection.execute(insert(models.DeviceCredential), {
            "id": "fixture-credential", "device_id": "fixture-device",
            "token_hash": service.digest(token), "state": "active", "operation_id": "fixture-pairing",
        })
    sessions = make_session_factory(pg_schema)
    waiting_on_device = threading.Event()

    def observe_device_lock(connection, cursor, statement, parameters, context, executemany):
        if (context.execution_options.get("fixture_heartbeat") and
                ".devices" in statement and "FOR UPDATE" in statement):
            waiting_on_device.set()

    event.listen(pg_schema, "before_cursor_execute", observe_device_lock)

    def heartbeat_auth():
        try:
            with sessions.begin() as db:
                db.connection(execution_options={"fixture_heartbeat": True})
                service.device_auth(db, "Bearer " + token, lock=True)
            return 200
        except HTTPException as exc:
            return exc.status_code

    executor = ThreadPoolExecutor(max_workers=1)
    try:
        with sessions.begin() as revoker:
            device = revoker.scalar(select(models.Device).where(models.Device.id == "fixture-device").with_for_update())
            competing = executor.submit(heartbeat_auth)
            assert waiting_on_device.wait(timeout=3), "Heartbeat did not reach its device lock"
            revoker.execute(text("SET LOCAL lock_timeout = '500ms'"))
            credential = revoker.scalar(select(models.DeviceCredential).where(
                models.DeviceCredential.id == "fixture-credential").with_for_update())
            device.status = "revoked"
            credential.revoked_at = time.time()
        assert competing.result(timeout=5) == 401
    finally:
        executor.shutdown(wait=True)
        event.remove(pg_schema, "before_cursor_execute", observe_device_lock)


def test_postgres_migration_catalog_has_all_control_tables(pg_url):
    """Checks the migrated public schema; fixtures above never replace it."""
    engine = make_engine(pg_url)
    try:
        inspector = inspect(engine)
        tables = set(inspector.get_table_names(schema="public"))
        assert "alembic_version" in tables, "Run alembic upgrade head before native integration tests"
        assert set(Base.metadata.tables) <= tables
        for table in Base.metadata.sorted_tables:
            columns = {item["name"]: item["nullable"] for item in inspector.get_columns(table.name, schema="public")}
            assert columns == {item.name: item.nullable for item in table.columns}, table.name
        with engine.connect() as connection:
            flags = dict(connection.execute(text(
                "SELECT c.relname, c.relrowsecurity FROM pg_class c "
                "JOIN pg_namespace n ON n.oid=c.relnamespace "
                "WHERE n.nspname='public' AND c.relkind='r'"
            )).all())
        assert all(flags.get(name) for name in Base.metadata.tables), "Every exposed control table requires RLS"
    finally:
        engine.dispose()


def test_postgres_unprivileged_role_cannot_read_control_records(pg_url):
    """A direct database/API role sees no rows even after a SELECT grant.

    The transient role, grants and canary record are all rolled back. This is
    deliberately limited to the disposable loopback test database above.
    """
    engine = make_engine(pg_url)
    role = "control_verify_role_" + uuid.uuid4().hex
    quoted_role = engine.dialect.identifier_preparer.quote(role)
    subject = "control-canary-" + uuid.uuid4().hex
    try:
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                connection.execute(text("CREATE ROLE " + quoted_role + " NOLOGIN"))
                connection.execute(text("GRANT USAGE ON SCHEMA public TO " + quoted_role))
                connection.execute(text("GRANT SELECT ON public.users TO " + quoted_role))
                connection.execute(insert(models.User), {
                    "id": subject, "email": "canary@example.invalid", "enabled": True,
                    "platform_owner": False,
                })
                assert connection.scalar(select(func.count()).select_from(models.User).where(models.User.id == subject)) == 1
                connection.execute(text("SET LOCAL ROLE " + quoted_role))
                assert connection.scalar(select(func.count()).select_from(models.User).where(models.User.id == subject)) == 0
            finally:
                transaction.rollback()
    finally:
        engine.dispose()


def test_postgres_runtime_role_has_data_access_without_administrative_access(pg_url):
    """Apply the reviewed role setup inside a disposable rollback transaction."""
    engine = make_engine(pg_url)
    script = (Path(__file__).resolve().parents[2] / "infra/v2/runtime-role.sql").read_text()
    # The deployment file owns its transaction. Let this test own that same
    # transaction so roles, policies, grants and all canary data roll back.
    assert script.count("BEGIN;") == 1 and script.rstrip().endswith("COMMIT;")
    body = script.replace("BEGIN;", "", 1).rsplit("COMMIT;", 1)[0]
    subject = "control-runtime-canary-" + uuid.uuid4().hex
    try:
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                for browser in ("anon", "authenticated"):
                    exists = connection.scalar(text("SELECT 1 FROM pg_roles WHERE rolname=:role"), {"role": browser})
                    if not exists:
                        connection.exec_driver_sql('CREATE ROLE "' + browser + '" NOLOGIN')
                connection.exec_driver_sql(body, execution_options={"no_parameters": True})
                flags = connection.execute(text(
                    "SELECT rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, rolreplication "
                    "FROM pg_roles WHERE rolname='manzanilla_control_api'"
                )).one()
                assert not any(flags)
                assert connection.scalar(text(
                    "SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid=c.relowner "
                    "JOIN pg_namespace n ON n.oid=c.relnamespace "
                    "WHERE n.nspname='public' AND r.rolname='manzanilla_control_api'"
                )) == 0
                assert not connection.scalar(text(
                    "SELECT has_schema_privilege('manzanilla_control_api', 'public', 'CREATE')"
                ))
                connection.execute(text("SET LOCAL ROLE manzanilla_control_api"))
                connection.execute(insert(models.User), {
                    "id": subject, "email": "runtime-canary@example.invalid", "enabled": True,
                    "platform_owner": False,
                })
                assert connection.scalar(select(models.User.email).where(models.User.id == subject)) == "runtime-canary@example.invalid"
                connection.execute(update(models.User).where(models.User.id == subject).values(enabled=False))
                assert connection.scalar(select(models.User.enabled).where(models.User.id == subject)) is False
                assert connection.scalar(text("SELECT count(*) FROM public.alembic_version")) == 1
                with pytest.raises(DBAPIError) as denied:
                    with connection.begin_nested():
                        connection.execute(text("CREATE TABLE public.control_forbidden_runtime_ddl(id integer)"))
                assert getattr(denied.value.orig, "sqlstate", None) == "42501"
                connection.execute(text("RESET ROLE"))
                for browser in ("anon", "authenticated"):
                    connection.exec_driver_sql('SET LOCAL ROLE "' + browser + '"')
                    with pytest.raises(DBAPIError) as denied:
                        with connection.begin_nested():
                            connection.execute(select(models.User.id).where(models.User.id == subject))
                    assert getattr(denied.value.orig, "sqlstate", None) == "42501"
                    connection.execute(text("RESET ROLE"))
            finally:
                transaction.rollback()
    finally:
        engine.dispose()
