"""Migrate only the isolated V2 database selected by MANZ_DATABASE_URL."""
import os
from urllib.parse import parse_qs, urlsplit
from alembic import context
from control import models, auth  # Register shared metadata, not the ASGI app.
from control.db import Base, make_engine
url = os.environ.get("MANZ_DATABASE_URL", "")
if not url:
    raise RuntimeError("MANZ_DATABASE_URL is required for migrations")
if os.environ.get("MANZ_ENVIRONMENT") == "production":
    if not url.startswith(("postgresql://", "postgresql+")):
        raise RuntimeError("Production migrations require PostgreSQL")
    if parse_qs(urlsplit(url).query).get("sslmode", [""])[-1] not in {"require", "verify-ca", "verify-full"}:
        raise RuntimeError("Production migrations require secure sslmode")
if url.startswith("postgres://"):
    url = "postgresql+psycopg://" + url[len("postgres://"):]
elif url.startswith("postgresql://"):
    url = "postgresql+psycopg://" + url[len("postgresql://"):]

if context.is_offline_mode():
    context.configure(url=url, target_metadata=Base.metadata,
                      literal_binds=True, dialect_opts={"paramstyle": "named"})
    with context.begin_transaction():
        context.run_migrations()
else:
    engine = make_engine(url)
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=Base.metadata,
                          compare_type=True, render_as_batch=engine.dialect.name == "sqlite")
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()
