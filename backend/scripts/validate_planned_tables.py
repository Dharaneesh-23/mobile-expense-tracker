"""Validate the additive SQL twice against PostgreSQL, then roll everything back."""
import sys
from pathlib import Path
from sqlalchemy import create_engine
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings

root = Path(__file__).resolve().parents[1]
cfg = Settings(_env_file=root / '.env.migration')
url = cfg.migration_database_url or cfg.database_url
engine = create_engine(url, connect_args={'connect_timeout': 10}, hide_parameters=True)
script = (root / 'sql/complete_planned_tables.sql').read_text(encoding='utf-8')
body = script.replace('\nBEGIN;\n', '\n', 1).rsplit('\nCOMMIT;', 1)[0]
expected = {'users','records','refresh_sessions','login_attempts','alembic_version',
            'bank_accounts','categories','transactions','budgets','split_groups','split_members','split_shares','sync_log'}
connection = None
try:
    connection = engine.raw_connection()
    driver = connection.driver_connection
    if driver.execute('SELECT current_user').fetchone()[0] != 'postgres':
        raise ValueError('Administrator connection required for rollback validation')
    driver.execute(body, prepare=False)
    driver.execute(body, prepare=False)
    for env in ('dev', 'qa'):
        names = {r[0] for r in driver.execute('SELECT table_name FROM information_schema.tables WHERE table_schema = %s AND table_type = \'BASE TABLE\'', (env,)).fetchall()}
        if not expected.issubset(names):
            raise ValueError('Missing tables after validation')
        count = driver.execute(f'SELECT count(*) FROM {env}.categories WHERE is_system').fetchone()[0]
        if count != 6:
            raise ValueError('Unexpected category seed count')
        print(f'{env}: all 13 expected tables and six system categories validated; rerun was idempotent.')
    driver.rollback()
    print('Validation rolled back: no tables or data were permanently added.')
except Exception:
    if connection is not None:
        connection.rollback()
    raise SystemExit('SQL validation failed; transaction rolled back. No credentials were printed.') from None
finally:
    if connection is not None:
        connection.close()
    engine.dispose()
