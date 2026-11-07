"""Deployment safety tests use only temporary fixture files and fake probes."""
from __future__ import annotations

import base64
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import control_deploy as deploy
from scripts import control_smoke as smoke


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "application.env"
        self.values = {
            "MANZ_ENVIRONMENT": "production", "MANZ_COOKIE_SECURE": "true",
            "MANZ_DOMAIN": "console.example.invalid", "MANZ_PUBLIC_ORIGIN": "https://console.example.invalid",
            "MANZ_DATABASE_URL": "postgresql+psycopg://fixture:private-fixture@db.example.invalid/control?sslmode=verify-full&sslrootcert=system",
            "MANZ_SUPABASE_URL": "https://identity.example.invalid",
            "MANZ_SUPABASE_PUBLISHABLE_KEY": "fixture-public-key",
            "MANZ_SUPABASE_SECRET_KEY": "fixture-secret-key",
            "MANZ_SESSION_ENCRYPTION_KEY": base64.urlsafe_b64encode(b"x" * 32).decode(),
        }
        self.write()
        self.migration_path = Path(self.tmp.name) / "migration.env"
        self.migration_values = {"MANZ_ENVIRONMENT": "production", "MANZ_DATABASE_URL":
            self.values["MANZ_DATABASE_URL"].replace("//fixture:", "//migration_role:")}
        self.migration_path.write_text("\n".join(key + "=" + repr(value) for key, value in self.migration_values.items()))
        self.migration_path.chmod(0o600)

    def write(self):
        self.path.write_text("\n".join(key + "=" + repr(value) for key, value in self.values.items()))
        self.path.chmod(0o600)

    def test_validates_without_printing_credentials(self):
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(deploy.main(["validate", "--env-file", str(self.path)]), 0)
        self.assertTrue(json.loads(output.getvalue())["valid"])
        self.assertNotIn("fixture-secret-key", output.getvalue())
        self.assertNotIn("private-fixture", output.getvalue())

    def test_rejects_group_readable_env_file(self):
        self.path.chmod(0o640)
        with self.assertRaises(deploy.ConfigurationError):
            deploy.read_env(self.path)

    def test_rejects_credential_file_in_checkout(self):
        with self.assertRaises(deploy.ConfigurationError):
            deploy.read_env(deploy.ROOT / "uncreated.env")

    def test_dotenv_does_not_execute_substitutions(self):
        self.values["MANZ_SUPABASE_SECRET_KEY"] = "$(touch /never-executed-by-parser)"
        self.write()
        self.assertEqual(deploy.read_env(self.path)["MANZ_SUPABASE_SECRET_KEY"], self.values["MANZ_SUPABASE_SECRET_KEY"])

    def test_duplicate_env_key_is_rejected(self):
        self.path.write_text(self.path.read_text() + "\nMANZ_DOMAIN=another.example.invalid\n")
        with self.assertRaises(deploy.ConfigurationError):
            deploy.read_env(self.path)

    def test_insecure_database_connection_is_rejected(self):
        self.values["MANZ_DATABASE_URL"] = self.values["MANZ_DATABASE_URL"].replace("sslmode=verify-full", "sslmode=require")
        with self.assertRaises(deploy.ConfigurationError):
            deploy.validate(self.values)

    def test_origin_must_match_the_deployment_hostname(self):
        self.values["MANZ_PUBLIC_ORIGIN"] = "https://another.example.invalid"
        with self.assertRaises(deploy.ConfigurationError):
            deploy.validate(self.values)

    def test_mutable_image_tags_are_rejected(self):
        with self.assertRaises(deploy.ConfigurationError):
            deploy.image("example.invalid/control:latest")
        self.assertEqual(deploy.image("example.invalid/control@sha256:" + "a" * 64), "example.invalid/control@sha256:" + "a" * 64)

    def test_plan_has_no_subprocess_or_deployment_side_effect(self):
        output = io.StringIO()
        with patch.object(deploy.subprocess, "run") as run, redirect_stdout(output):
            code = deploy.main(["plan", "--env-file", str(self.path), "--migration-env-file", str(self.migration_path), "--api-image", "example.invalid/control@sha256:" + "a" * 64,
                                "--portal-image", "example.invalid/portal@sha256:" + "b" * 64])
        self.assertEqual(code, 0)
        run.assert_not_called()
        self.assertFalse(json.loads(output.getvalue())["deploys"])

    def test_apply_requires_matching_environment_confirmation(self):
        errors = io.StringIO()
        with patch.object(deploy.subprocess, "run") as run, redirect_stderr(errors):
            code = deploy.main(["deploy", "--env-file", str(self.path), "--migration-env-file", str(self.migration_path), "--environment", "production",
                                "--confirm-environment", "staging", "--api-image", "example.invalid/control@sha256:" + "a" * 64,
                                "--portal-image", "example.invalid/portal@sha256:" + "b" * 64])
        self.assertEqual(code, 1)
        run.assert_not_called()
        self.assertNotIn("private-fixture", errors.getvalue())

    def test_migration_role_is_separate_and_targets_same_database(self):
        deploy.validate_migration(self.migration_values, self.values)
        with self.assertRaises(deploy.ConfigurationError):
            deploy.validate_migration({"MANZ_ENVIRONMENT": "production", "MANZ_DATABASE_URL": self.values["MANZ_DATABASE_URL"]}, self.values)
        changed = {**self.migration_values, "MANZ_DATABASE_URL": self.migration_values["MANZ_DATABASE_URL"].replace("db.example.invalid", "other.example.invalid")}
        with self.assertRaises(deploy.ConfigurationError):
            deploy.validate_migration(changed, self.values)

    def test_migration_env_must_not_copy_provider_secrets(self):
        with self.assertRaises(deploy.ConfigurationError):
            deploy.validate_migration({**self.migration_values, "MANZ_SUPABASE_SECRET_KEY": "fixture"}, self.values)

    def test_shared_pooler_hostname_does_not_prove_same_project(self):
        runtime = {**self.values, "MANZ_DATABASE_URL": "postgresql+psycopg://api.project-a:fixture@aws-0-eu.pooler.supabase.com:5432/postgres?sslmode=verify-full"}
        migration = {"MANZ_ENVIRONMENT": "production", "MANZ_DATABASE_URL": "postgresql+psycopg://ddl.project-b:fixture@aws-0-eu.pooler.supabase.com:5432/postgres?sslmode=verify-full"}
        with self.assertRaises(deploy.ConfigurationError):
            deploy.validate_migration(migration, runtime)

    def test_migration_container_uses_ddl_file_without_replacing_runtime_file(self):
        with patch.object(deploy.subprocess, "run") as run, redirect_stdout(io.StringIO()):
            code = deploy.main(["deploy", "--env-file", str(self.path), "--migration-env-file", str(self.migration_path),
                                "--environment", "staging", "--confirm-environment", "staging",
                                "--api-image", "example.invalid/control@sha256:" + "a" * 64,
                                "--portal-image", "example.invalid/portal@sha256:" + "b" * 64])
        self.assertEqual(code, 0)
        migrations = [call for call in run.call_args_list if "alembic" in call.args[0]]
        self.assertEqual(len(migrations), 1)
        self.assertEqual(migrations[0].kwargs["env"]["MANZ_ENV_FILE"], str(self.migration_path))
        for call in run.call_args_list:
            if "alembic" not in call.args[0]:
                self.assertEqual(call.kwargs["env"]["MANZ_ENV_FILE"], str(self.path))

    def test_rollback_does_not_downgrade_or_delete_database(self):
        commands = deploy.compose_commands("staging", rollback=True)
        flat = " ".join(word for command in commands for word in command)
        self.assertNotIn("downgrade", flat)
        self.assertNotIn("down", flat)
        self.assertNotIn("-v", flat)

    def test_deploy_checks_public_tls_portal_after_local_api(self):
        commands = deploy.compose_commands("staging", public_origin="https://console.example.invalid")
        self.assertIn("http://127.0.0.1:8080", commands[-2])
        self.assertIn("https://console.example.invalid", commands[-1])
        self.assertIn("--portal", commands[-1])


class SmokeTests(unittest.TestCase):
    def test_readiness_checks_also_require_unauthenticated_denial(self):
        def response(base, path):
            return (200, {"status": "ok"}) if path.startswith("/health/") else (401, None)
        with patch.object(smoke, "fetch", side_effect=response):
            smoke.check("https://console.example.invalid")

    def test_unprotected_hotel_listing_fails_smoke(self):
        def response(base, path):
            if path == "/api/v2/hotels":
                return 200, {"items": []}
            return (200, {"status": "ok"}) if path.startswith("/health/") else (401, None)
        with patch.object(smoke, "fetch", side_effect=response), self.assertRaises(ValueError):
            smoke.check("https://console.example.invalid")

    def test_plain_http_is_allowed_only_for_loopback(self):
        with redirect_stderr(io.StringIO()), patch.object(smoke, "check") as check:
            self.assertEqual(smoke.main(["--base-url", "http://private.example.invalid"]), 1)
            check.assert_not_called()

    def test_public_probe_requires_portal_document(self):
        def response(base, path):
            return (200, {"status": "ok"}) if path.startswith("/health/") else (401, None)
        with patch.object(smoke, "fetch", side_effect=response), patch.object(smoke, "check_portal", side_effect=ValueError("missing")), self.assertRaises(ValueError):
            smoke.check("https://console.example.invalid", portal=True)


if __name__ == "__main__":
    unittest.main()
