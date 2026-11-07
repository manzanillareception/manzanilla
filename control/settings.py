"""Fail-closed control-plane configuration; secrets never appear in repr()."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import parse_qs, urlsplit

from cryptography.fernet import Fernet


def _origin(value: str, *, production: bool) -> str:
    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"https", "http"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError("public_origin must be an HTTP(S) origin without credentials or a path")
    if production and parsed.scheme != "https":
        raise ValueError("production public_origin requires HTTPS")
    return value.rstrip("/")


def _boolean(value: str, name: str) -> bool:
    if value.lower() in {"true", "1"}:
        return True
    if value.lower() in {"false", "0"}:
        return False
    raise ValueError(f"{name} must be true or false")


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default="sqlite+pysqlite:///:memory:", repr=False)
    public_origin: str = "http://localhost:8080"
    environment: str = "development"
    supabase_url: str = ""
    supabase_publishable_key: str = field(default="", repr=False)
    supabase_secret_key: str = field(default="", repr=False)
    session_encryption_key: str = field(default="", repr=False)
    bootstrap_owner_sub: str = ""
    session_ttl_seconds: int = 3600
    cookie_secure: bool | None = None

    def __post_init__(self) -> None:
        if self.environment not in {"development", "test", "production"}:
            raise ValueError("environment must be development, test or production")
        production = self.environment == "production"
        object.__setattr__(self, "public_origin", _origin(self.public_origin, production=production))
        if not 300 <= self.session_ttl_seconds <= 43200:
            raise ValueError("session_ttl_seconds must be between 300 and 43200")
        if production and not self.database_url.startswith(("postgresql://", "postgresql+")):
            raise ValueError("production requires PostgreSQL")
        if production and parse_qs(urlsplit(self.database_url).query).get("sslmode", [""])[-1] not in {"require", "verify-ca", "verify-full"}:
            raise ValueError("production PostgreSQL requires an explicit secure sslmode")
        if production and not all((self.supabase_url, self.supabase_publishable_key,
                                   self.supabase_secret_key, self.session_encryption_key)):
            raise ValueError("production Supabase and encryption configuration is required")
        if self.supabase_url:
            parsed = urlsplit(self.supabase_url)
            if (parsed.scheme != "https" or not parsed.hostname or parsed.username or
                    parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
                raise ValueError("supabase_url requires an HTTPS origin")
            object.__setattr__(self, "supabase_url", self.supabase_url.rstrip("/"))
        if not self.session_encryption_key:
            # Isolated local development only: restart intentionally invalidates sessions.
            object.__setattr__(self, "session_encryption_key", Fernet.generate_key().decode())
        try:
            Fernet(self.session_encryption_key.encode("ascii"))
        except (ValueError, UnicodeError) as exc:
            raise ValueError("session_encryption_key must be a Fernet key") from exc
        secure = self.cookie_secure
        if secure is None:
            secure = self.public_origin.startswith("https://")
        if production and not secure:
            raise ValueError("production requires secure cookies")
        object.__setattr__(self, "cookie_secure", secure)
        if self.bootstrap_owner_sub and len(self.bootstrap_owner_sub) > 128:
            raise ValueError("bootstrap_owner_sub is too long")

    @classmethod
    def from_env(cls) -> "Settings":
        values: dict[str, object] = {}
        for name in cls.__dataclass_fields__:
            value = os.environ.get("MANZ_" + name.upper())
            if value is None:
                continue
            if name == "cookie_secure":
                values[name] = _boolean(value, "MANZ_COOKIE_SECURE")
            elif name == "session_ttl_seconds":
                values[name] = int(value)
            else:
                values[name] = value
        return cls(**values)
