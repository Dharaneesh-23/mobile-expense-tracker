"""Test private APK delivery with a disposable QA account and exact-ID cleanup."""
import argparse
import hashlib
import secrets
import sys
import uuid
from pathlib import Path

import httpx
from sqlalchemy import delete
from sqlalchemy.orm import Session

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.database import make_engine
from app.models import LoginAttempt, RefreshSession, User
from app.security import digest, passwords


def check(base_url, channels):
    cfg = Settings(_env_file=Path(__file__).resolve().parents[1] / '.env.qa')
    if cfg.app_env != 'qa' or cfg.db_schema != 'qa':
        raise ValueError('Only QA may be tested')
    if not base_url.startswith('https://'):
        raise ValueError('An HTTPS service is required')
    engine = make_engine(cfg.database_url, 'qa')
    uid = str(uuid.uuid4())
    email = f'update-smoke-{uid}@example.com'
    password = secrets.token_urlsafe(24)
    try:
        with Session(engine) as db:
            db.add(User(id=uid, email=email, password_hash=passwords.hash(password), revision=0))
            db.commit()
        with httpx.Client(base_url=base_url.rstrip('/'), timeout=120, follow_redirects=False) as client:
            health = client.get('/health')
            assert health.status_code == 200 and health.json()['environment'] == 'qa'
            assert client.get('/api/v1/app-updates/qa').status_code == 401
            login = client.post('/api/v1/auth/login', json={'email': email, 'password': password})
            assert login.status_code == 200, 'QA login failed'
            headers = {'Authorization': 'Bearer ' + login.json()['access_token']}
            for channel in channels:
                metadata = client.get(f'/api/v1/app-updates/{channel}', headers=headers)
                assert metadata.status_code == 200, f'{channel} metadata failed: HTTP {metadata.status_code}'
                manifest = metadata.json()
                expected_package = 'com.dharaneesh.expensetracker' + ('.qa' if channel == 'qa' else '')
                assert manifest['channel'] == channel and manifest['package_name'] == expected_package
                assert manifest['version_code'] > 100
                target = Path(__file__).resolve().parents[2] / f'android/build/online-updates/{manifest["apk_name"]}'
                target.parent.mkdir(parents=True, exist_ok=True)
                sha = hashlib.sha256()
                size = 0
                with client.stream('GET', manifest['download_path'], headers=headers) as response:
                    assert response.status_code == 200, f'{channel} download failed: HTTP {response.status_code}'
                    with target.open('wb') as output:
                        for chunk in response.iter_bytes():
                            size += len(chunk)
                            assert size <= manifest['size']
                            sha.update(chunk)
                            output.write(chunk)
                assert size == manifest['size'] and sha.hexdigest() == manifest['sha256']
                print(f'{channel}: authenticated metadata and APK stream passed; version {manifest["version_code"]}, SHA-256 and size verified.')
            print('QA health, authentication and unauthenticated update rejection passed.')
    finally:
        with Session(engine) as db:
            db.execute(delete(RefreshSession).where(RefreshSession.user_id == uid))
            db.execute(delete(User).where(User.id == uid))
            db.execute(delete(LoginAttempt).where(LoginAttempt.key == digest(email)))
            db.commit()
        engine.dispose()
        print('Disposable update-test account and sessions removed; personal accounts were untouched.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True)
    parser.add_argument('--channels', nargs='+', choices=['qa', 'prod'], default=['qa', 'prod'])
    args = parser.parse_args()
    try:
        check(args.url, args.channels)
    except Exception as error:
        # Do not expose request headers, tokens, passwords, or database URLs.
        raise SystemExit(f'Online update verification failed ({type(error).__name__}); credentials were not printed.') from None
