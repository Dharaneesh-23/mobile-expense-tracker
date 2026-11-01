"""Read-only live connection check. Never prints connection URLs or passwords."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from app.config import settings
from app.database import engine

cfg = settings()
if engine.dialect.name != 'postgresql':
    raise SystemExit('Configure a PostgreSQL DATABASE_URL in the ignored .env first.')
other = 'qa' if cfg.db_schema == 'dev' else 'dev'
try:
    with engine.connect() as connection:
        own_usage = connection.scalar(text('SELECT has_schema_privilege(current_user, :schema, \'USAGE\')'), {'schema': cfg.db_schema})
        other_usage = connection.scalar(text('SELECT has_schema_privilege(current_user, :schema, \'USAGE\')'), {'schema': other})
        tables = connection.execute(text('SELECT table_name FROM information_schema.tables WHERE table_schema = :schema'), {'schema':cfg.db_schema}).scalars().all()
        if not own_usage or other_usage:
            raise SystemExit('Role isolation failed: use the environment-specific runtime role and review schema grants.')
        required = {'users', 'records', 'refresh_sessions', 'login_attempts'}
        if not required.issubset(set(tables)):
            raise SystemExit('Connection and schema isolation verified; run migrations before API testing.')
        print(f'{cfg.app_env}: database connection, schema isolation, and required tables verified.')
except SQLAlchemyError:
    raise SystemExit('Database check failed. Check credentials, TLS, pooler hostname, schema grants, and project status. Connection details were not printed.') from None
