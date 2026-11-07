"""Validate or deliberately deploy the V2 control plane on its intended VM.

No SSH, credential generation, invitation or deployment happens on import. The
default/validate/plan commands are read-only. Apply commands require explicit
environment confirmation and immutable image digests. Secrets are never printed.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import sys
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / "infra" / "v2" / "compose.yml"
REQUIRED = (
    "MANZ_DOMAIN", "MANZ_PUBLIC_ORIGIN", "MANZ_DATABASE_URL",
    "MANZ_SUPABASE_URL", "MANZ_SUPABASE_PUBLISHABLE_KEY",
    "MANZ_SUPABASE_SECRET_KEY", "MANZ_SESSION_ENCRYPTION_KEY",
)
DIGEST = re.compile(r"[a-z0-9][a-z0-9._:/-]*@sha256:[a-f0-9]{64}\Z")


class ConfigurationError(ValueError):
    pass


def read_env(path: Path) -> dict[str, str]:
    """Parse simple dotenv without executing substitutions or shell code."""
    if path.is_symlink():
        raise ConfigurationError("Application env file must not be a symlink")
    path = path.resolve()
    if path == ROOT or ROOT in path.parents:
        raise ConfigurationError("Keep the application env file outside the checkout")
    try:
        info = path.stat()
    except OSError as exc:
        raise ConfigurationError("Cannot read the protected application env file") from exc
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise ConfigurationError("Application env file must be a regular owner-only file (chmod 600)")
    if hasattr(os, "getuid") and info.st_uid != os.getuid():
        raise ConfigurationError("Application env file must belong to the deploying user")
    result: dict[str, str] = {}
    for number, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or not re.fullmatch(r"MANZ_[A-Z0-9_]+", key) or key in result:
            raise ConfigurationError(f"Invalid or duplicate configuration key on line {number}")
        try:
            parts = shlex.split(value, comments=True, posix=True)
        except ValueError as exc:
            raise ConfigurationError(f"Invalid quoting on configuration line {number}") from exc
        if len(parts) > 1:
            raise ConfigurationError(f"Quote the value on configuration line {number}")
        result[key] = parts[0] if parts else ""
    return result


def validate(values: dict[str, str]) -> dict[str, object]:
    for key in REQUIRED:
        value = values.get(key, "")
        if not value or "REPLACE" in value.upper() or "\n" in value:
            raise ConfigurationError(f"Missing or placeholder configuration: {key}")
    if values.get("MANZ_ENVIRONMENT") != "production":
        raise ConfigurationError("Staging and production deploys must use production validation")
    if values.get("MANZ_COOKIE_SECURE", "").lower() != "true":
        raise ConfigurationError("MANZ_COOKIE_SECURE must be true")
    domain = values["MANZ_DOMAIN"]
    if len(domain) > 253 or not re.fullmatch(r"(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}", domain):
        raise ConfigurationError("MANZ_DOMAIN must be the actual owned DNS hostname")
    origin = urlsplit(values["MANZ_PUBLIC_ORIGIN"])
    if (origin.scheme != "https" or origin.netloc != domain or origin.path not in ("", "/")
            or origin.query or origin.fragment or origin.username or origin.password):
        raise ConfigurationError("MANZ_PUBLIC_ORIGIN must be https://MANZ_DOMAIN")
    upstream = urlsplit(values["MANZ_SUPABASE_URL"])
    if (upstream.scheme != "https" or not upstream.hostname or upstream.username
            or upstream.password or upstream.query or upstream.fragment or upstream.path not in ("", "/")):
        raise ConfigurationError("MANZ_SUPABASE_URL must be an HTTPS project URL")
    database = urlsplit(values["MANZ_DATABASE_URL"])
    if (database.scheme != "postgresql+psycopg" or not database.hostname
            or not database.username or not database.password or not database.path.strip("/")):
        raise ConfigurationError("MANZ_DATABASE_URL must be an authenticated PostgreSQL psycopg URL")
    if parse_qs(database.query).get("sslmode") != ["verify-full"]:
        raise ConfigurationError("MANZ_DATABASE_URL must use sslmode=verify-full with the provider CA")
    try:
        if len(base64.urlsafe_b64decode(values["MANZ_SESSION_ENCRYPTION_KEY"].encode())) != 32:
            raise ValueError()
    except (ValueError, UnicodeError) as exc:
        raise ConfigurationError("MANZ_SESSION_ENCRYPTION_KEY must be a valid Fernet key") from exc
    return {"valid": True, "domain": domain, "environment": "production", "database_configured": True}


def image(value: str) -> str:
    if not DIGEST.fullmatch(value):
        raise ConfigurationError("Runtime images must be explicit repository@sha256 digests")
    return value


def validate_migration(values: dict[str, str], runtime: dict[str, str]) -> None:
    """Keep DDL credentials separate and bind migration to the same database."""
    if set(values) - {"MANZ_ENVIRONMENT", "MANZ_DATABASE_URL"}:
        raise ConfigurationError("Migration env file must contain database configuration only")
    if values.get("MANZ_ENVIRONMENT") != "production":
        raise ConfigurationError("Migration environment must use production validation")
    value = values.get("MANZ_DATABASE_URL", "")
    if not value or "REPLACE" in value.upper():
        raise ConfigurationError("Missing migration database configuration")
    migration = urlsplit(value)
    normal = urlsplit(runtime["MANZ_DATABASE_URL"])
    if (migration.scheme != "postgresql+psycopg" or not migration.hostname or not migration.username
            or not migration.password or parse_qs(migration.query).get("sslmode") != ["verify-full"]):
        raise ConfigurationError("Migration requires an authenticated PostgreSQL psycopg URL with sslmode=verify-full")
    if (migration.hostname, migration.port or 5432, migration.path) != (normal.hostname, normal.port or 5432, normal.path):
        raise ConfigurationError("Migration and runtime must target the same database host, port and database")
    if migration.hostname.endswith(".pooler.supabase.com"):
        migration_project = migration.username.rsplit(".", 1)
        runtime_project = normal.username.rsplit(".", 1)
        if len(migration_project) != 2 or len(runtime_project) != 2 or migration_project[-1] != runtime_project[-1]:
            raise ConfigurationError("Supabase pooler credentials must target the same project")
    if migration.username == normal.username:
        raise ConfigurationError("Migration must use a separate database role from the runtime API")


def compose_commands(environment: str, *, rollback: bool = False, public_origin: str | None = None) -> list[list[str]]:
    base = ["docker", "compose", "--project-name", f"manzanilla-control-{environment}", "--file", str(COMPOSE)]
    commands = [base + ["pull"]]
    if not rollback:
        commands.append(base + ["run", "--rm", "--no-deps", "api", "alembic", "-c", "control/alembic.ini", "upgrade", "head"])
    commands.append(base + ["up", "-d", "--remove-orphans"])
    commands.append([sys.executable, str(ROOT / "scripts" / "control_smoke.py"), "--base-url", "http://127.0.0.1:8080", "--wait-seconds", "60"])
    if public_origin:
        commands.append([sys.executable, str(ROOT / "scripts" / "control_smoke.py"), "--base-url", public_origin, "--portal", "--wait-seconds", "60"])
    return commands


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["validate", "plan", "deploy", "rollback"], default="validate", nargs="?")
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--migration-env-file", type=Path)
    parser.add_argument("--environment", choices=["staging", "production"], default="staging")
    parser.add_argument("--api-image")
    parser.add_argument("--portal-image")
    parser.add_argument("--confirm-environment", choices=["staging", "production"])
    parser.add_argument("--confirm-compatible-schema", action="store_true")
    args = parser.parse_args(argv)
    try:
        values = read_env(args.env_file)
        report = validate(values)
        migration_path = None
        if args.migration_env_file:
            validate_migration(read_env(args.migration_env_file), values)
            migration_path = str(args.migration_env_file.resolve())
            report["migration_configured"] = True
        if args.action == "validate":
            print(json.dumps(report))
            return 0
        api_image, portal_image = image(args.api_image or ""), image(args.portal_image or "")
        if args.action == "rollback" and not args.confirm_compatible_schema:
            raise ConfigurationError("Application rollback requires --confirm-compatible-schema; no database downgrade is automatic")
        if args.action != "rollback" and not migration_path:
            raise ConfigurationError("Plan/deploy requires a separate protected --migration-env-file")
        commands = compose_commands(args.environment, rollback=args.action == "rollback", public_origin=values["MANZ_PUBLIC_ORIGIN"])
        if args.action == "plan":
            print(json.dumps({"environment": args.environment, "commands": commands, "deploys": False,
                              "migration_uses_separate_env": bool(migration_path)}))
            return 0
        if args.confirm_environment != args.environment:
            raise ConfigurationError("Apply requires --confirm-environment matching the chosen environment")
        env = dict(os.environ)
        env.update({"MANZ_ENV_FILE": str(args.env_file.resolve()), "MANZ_DOMAIN": values["MANZ_DOMAIN"],
                    "MANZ_CONTROL_IMAGE": api_image, "MANZ_PORTAL_IMAGE": portal_image})
        for command in commands:
            command_env = dict(env)
            if "alembic" in command:
                command_env["MANZ_ENV_FILE"] = migration_path
            subprocess.run(command, cwd=ROOT, env=command_env, check=True)
        print(json.dumps({"environment": args.environment, "applied": True}))
        return 0
    except ConfigurationError as exc:
        # These messages contain fixed labels/key names, never input values.
        print(str(exc), file=sys.stderr)
        return 1
    except (ValueError, OSError, subprocess.CalledProcessError):
        # Do not expose command output, database URLs or environment values.
        print("Control deployment failed. Validate protected configuration, reviewed image digests, migration status and local service readiness.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
