import pytest
from cryptography.fernet import Fernet

from control.settings import Settings


def production_values():
    return dict(environment="production", database_url="postgresql+psycopg://app:password@db.example/app?sslmode=verify-full",
                public_origin="https://console.example", supabase_url="https://project.supabase.co",
                supabase_publishable_key="public-test-key", supabase_secret_key="private-test-key",
                session_encryption_key=Fernet.generate_key().decode())


def test_production_settings_accept_only_explicit_secure_configuration():
    settings = Settings(**production_values())
    assert settings.cookie_secure is True
    assert "private-test-key" not in repr(settings)
    assert "password@" not in repr(settings)


@pytest.mark.parametrize("changes", [
    {"database_url": "sqlite:///production.db"},
    {"database_url": "postgresql://app@db.example/db"},
    {"database_url": "postgresql://app@db.example/db?sslmode=disable"},
    {"public_origin": "http://console.example"},
    {"public_origin": "https://user:password@console.example"},
    {"public_origin": "https://console.example/path"},
    {"cookie_secure": False},
    {"supabase_secret_key": ""},
    {"session_encryption_key": ""},
    {"session_encryption_key": "invalid"},
    {"supabase_url": "http://project.supabase.co"},
    {"session_ttl_seconds": 60},
])
def test_production_settings_reject_unsafe_values(changes):
    with pytest.raises(ValueError):
        Settings(**(production_values() | changes))


def test_environment_reading_does_not_override_process_settings(monkeypatch):
    monkeypatch.setenv("MANZ_ENVIRONMENT", "test")
    monkeypatch.setenv("MANZ_PUBLIC_ORIGIN", "http://localhost:9000/")
    monkeypatch.setenv("MANZ_COOKIE_SECURE", "false")
    monkeypatch.setenv("MANZ_SESSION_TTL_SECONDS", "900")
    settings = Settings.from_env()
    assert settings.public_origin == "http://localhost:9000"
    assert settings.cookie_secure is False
    assert settings.session_ttl_seconds == 900
    monkeypatch.setenv("MANZ_COOKIE_SECURE", "invalid")
    with pytest.raises(ValueError):
        Settings.from_env()
