"""Prepare an ignored, runtime-only Render import file; never print secret values."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app.config import Settings

root=Path(__file__).resolve().parents[1]
destination=root.parent/'private-data/render-qa.env'
try:
    cfg=Settings(_env_file=root/'.env.qa')
    if cfg.app_env!='qa' or cfg.db_schema!='qa':raise ValueError('Wrong environment')
    token=cfg.updates_github_token.get_secret_value()
    if not token or token.startswith('REPLACE'):raise ValueError('Configure the read-only update token first')
    # Do not import any administrator connection or the local workflow credential.
    from sqlalchemy.engine import make_url
    if not (make_url(cfg.database_url).username or '').startswith('expense_qa_app'):
        raise ValueError('The QA database URL must use the restricted runtime role')
    values={'APP_ENV':'qa','DB_SCHEMA':'qa','DATABASE_URL':cfg.database_url,'JWT_SECRET_KEY':cfg.jwt_secret_key,
            'ALLOW_REGISTRATION':'false','UPDATES_GITHUB_REPOSITORY':cfg.updates_github_repository,
            'UPDATES_GITHUB_TOKEN':token}
    if any('\n' in value or '\r' in value for value in values.values()):raise ValueError('Unexpected multiline value')
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(''.join(f'{key}={value}\n' for key,value in values.items()),encoding='utf-8')
    print('Prepared private-data/render-qa.env with restricted QA runtime settings only. Values were not printed.')
except Exception:
    raise SystemExit('Render import preparation failed. Check the QA runtime role and read-only update token; no secret values were printed.') from None
