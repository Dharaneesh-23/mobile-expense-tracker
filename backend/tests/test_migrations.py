import os
import subprocess
import sys
from pathlib import Path
from sqlalchemy import create_engine,inspect,text

def test_fresh_database_upgrade_and_repeat_upgrade(tmp_path):
    destination=tmp_path/'migration.sqlite'
    env=dict(os.environ,DATABASE_URL='sqlite:///'+destination.as_posix(),MIGRATION_DATABASE_URL='',
             APP_ENV='dev',DB_SCHEMA='dev',JWT_SECRET_KEY='test-migration-secret-at-least-32-characters')
    # An empty migration override must fall back to the supplied SQLite URL.
    backend=Path(__file__).resolve().parents[1]
    for _ in range(2):
        result=subprocess.run([sys.executable,'-m','alembic','upgrade','head'],cwd=backend,env=env,capture_output=True,text=True)
        assert result.returncode==0,result.stderr
    engine=create_engine('sqlite:///'+destination.as_posix())
    inspector=inspect(engine)
    assert len(inspector.get_table_names())==13
    for table in ('transactions','budgets','split_shares'):
        assert 'sync_record_id' in {c['name'] for c in inspector.get_columns(table)}
    with engine.connect() as connection:
        assert connection.scalar(text('select version_num from alembic_version'))=='0002'
    engine.dispose()
