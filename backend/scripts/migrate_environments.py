"""Run additive Alembic migrations for dev/qa with separately stored admin credentials."""
import os
import subprocess
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app.config import Settings

root=Path(__file__).resolve().parents[1]
try:
    admin=Settings(_env_file=root/'.env.migration')
    for environment in ('dev','qa'):
        runtime=Settings(_env_file=root/f'.env.{environment}')
        variables=dict(os.environ,APP_ENV=environment,DB_SCHEMA=environment,
                       DATABASE_URL=runtime.database_url,
                       MIGRATION_DATABASE_URL=admin.migration_database_url or admin.database_url,
                       JWT_SECRET_KEY=runtime.jwt_secret_key)
        result=subprocess.run([sys.executable,'-m','alembic','upgrade','head'],cwd=root,env=variables,capture_output=True,text=True)
        if result.returncode:
            raise SystemExit(f'{environment}: migration failed. Credentials were not printed.')
        print(f'{environment}: migrated to the current Alembic revision.')
except Exception:
    raise SystemExit('Environment migration failed. No credentials were printed.') from None
