"""One-time provisioning of empty dev/qa schemas using locally supplied admin credentials.

Does not print secrets. Refuses to reset credentials once application tables exist.
"""
import os
import secrets
import subprocess
import sys
from pathlib import Path
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from psycopg import sql

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
from app.config import settings

def main():
    if sys.argv[1:] != ['--apply']:
        raise SystemExit('Use --apply only for initial empty-schema setup.')
    cfg = settings()
    admin_url = cfg.migration_database_url or cfg.database_url
    url = make_url(admin_url)
    if url.get_backend_name() != 'postgresql':
        raise SystemExit('A PostgreSQL administrator connection is required.')
    admin = create_engine(admin_url, connect_args={'connect_timeout':10}, hide_parameters=True)
    with admin.connect() as connection:
        role = connection.scalar(text('select current_user'))
        existing = connection.scalar(text("select count(*) from information_schema.tables where table_schema in ('dev','qa')"))
        if role != 'postgres' or existing:
            raise SystemExit('Refusing setup: requires postgres and empty dev/qa schemas.')
    destinations = [root / '.env.migration', root / '.env.dev', root / '.env.qa']
    if any(p.exists() for p in destinations):
        raise SystemExit('Refusing to overwrite existing local environment configuration files.')
    current_env = root / '.env'
    backup = current_env.read_text(encoding='utf-8-sig')
    (root / '.env.migration').write_text(backup, encoding='utf-8')
    is_pooler = bool(url.host and '.pooler.supabase.com' in url.host)
    ref = url.username.split('.',1)[1] if is_pooler and '.' in (url.username or '') else None
    if is_pooler and not ref:
        raise SystemExit('Pooler administrator username must include the project reference.')
    passwords = {env: secrets.token_urlsafe(36) for env in ('dev','qa')}
    for env in ('dev','qa'):
        username = f'expense_{env}_app' + ('.'+ref if ref else '')
        runtime_url = url.set(username=username, password=passwords[env]).render_as_string(hide_password=False)
        content = f'APP_ENV={env}\nDB_SCHEMA={env}\nDATABASE_URL={runtime_url}\nJWT_SECRET_KEY={secrets.token_urlsafe(48)}\nALLOW_REGISTRATION=true\n'
        (root / f'.env.{env}').write_text(content, encoding='utf-8')
    with admin.begin() as connection:
        driver = connection.connection.driver_connection
        driver.execute((root / 'sql/bootstrap_schemas.sql').read_text(encoding='utf-8'), prepare=False)
        for env in ('dev','qa'):
            driver.execute(sql.SQL('ALTER ROLE {} PASSWORD {}').format(sql.Identifier(f'expense_{env}_app'), sql.Literal(passwords[env])), prepare=False)
    for env in ('dev','qa'):
        process_env = dict(os.environ, APP_ENV=env, DB_SCHEMA=env, DATABASE_URL=admin_url,
                           MIGRATION_DATABASE_URL=admin_url, JWT_SECRET_KEY=secrets.token_urlsafe(48))
        result = subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', 'head'], cwd=root,
                                env=process_env, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(f'{env} migration failed. Local credentials were retained; secrets were not printed.')
        print(f'{env}: initial migration applied.')
    current_env.write_text((root / '.env.dev').read_text(encoding='utf-8'), encoding='utf-8')
    print('Dev/QA schemas migrated. Runtime credentials are in ignored .env.dev/.env.qa; .env now uses dev.')
    print('Original admin configuration is isolated in ignored .env.migration. Keep it private and out of Render.')

if __name__ == '__main__':
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        raise SystemExit('Provisioning failed; local credential files were retained. No credentials were printed.') from None
