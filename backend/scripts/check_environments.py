"""Verify dev/QA schema privilege boundaries using ignored local runtime configurations."""
import sys
from pathlib import Path
from sqlalchemy import text,inspect
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.database import make_engine

root = Path(__file__).resolve().parents[1]
try:
    for env, other in [('dev','qa'),('qa','dev')]:
        cfg = Settings(_env_file=root/f'.env.{env}')
        engine = make_engine(cfg.database_url, cfg.db_schema)
        with engine.connect() as connection:
            role = connection.scalar(text('select current_user'))
            if role != f'expense_{env}_app': raise ValueError('Unexpected role')
            if not connection.scalar(text('select has_schema_privilege(current_user, :schema, \'USAGE\')'), {'schema':env}): raise ValueError('Missing own access')
            if connection.scalar(text('select has_schema_privilege(current_user, :schema, \'USAGE\')'), {'schema':other}): raise ValueError('Cross-schema privilege')
            connection.execute(text(f'SELECT id FROM "{env}".users LIMIT 0'))
            connection.execute(text(f'SELECT id FROM "{env}".records LIMIT 0'))
            expected={'users','records','refresh_sessions','login_attempts','alembic_version','bank_accounts',
                      'categories','transactions','budgets','split_groups','split_members','split_shares','sync_log'}
            inspector=inspect(connection)
            if not expected.issubset(set(inspector.get_table_names(schema=env))):raise ValueError('Missing relational tables')
            for table in ('transactions','budgets','split_shares'):
                if 'sync_record_id' not in {c['name'] for c in inspector.get_columns(table,schema=env)}:raise ValueError('Missing sync identity link')
            if connection.scalar(text(f'SELECT version_num FROM "{env}".alembic_version'))!='0002':raise ValueError('Migrations are not current')
        engine.dispose()
        print(f'{env}: all 13 tables, relational sync links, migration 0002, and schema boundary verified.')
except Exception:
    raise SystemExit('Environment verification failed. No credentials were printed.') from None
