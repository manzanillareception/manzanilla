from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text

from control import auth, models
from control.db import Base, make_engine


def test_frozen_migration_upgrade_downgrade_upgrade_and_schema(tmp_path, monkeypatch):
    database = "sqlite+pysqlite:///" + str(tmp_path / "migration.sqlite")
    monkeypatch.setenv("MANZ_DATABASE_URL", database)
    monkeypatch.setenv("MANZ_ENVIRONMENT", "test")
    config = Config("control/alembic.ini")
    command.upgrade(config, "head")
    command.check(config)
    engine = make_engine(database)
    tables = set(inspect(engine).get_table_names())
    assert set(Base.metadata.tables) <= tables
    for name, table in Base.metadata.tables.items():
        actual = inspect(engine).get_columns(name)
        assert {c["name"] for c in actual} == set(table.columns.keys())
    with engine.connect() as db:
        assert db.scalar(text("SELECT version_num FROM alembic_version")) == "0001_control"
        assert db.scalar(text("SELECT COUNT(*) FROM worker_leases")) == 1
        assert db.scalar(text("SELECT COUNT(*) FROM worker_progress")) == 1
    engine.dispose()
    command.downgrade(config, "base")
    command.upgrade(config, "head")
    engine = make_engine(database)
    assert set(Base.metadata.tables) <= set(inspect(engine).get_table_names())
    engine.dispose()
