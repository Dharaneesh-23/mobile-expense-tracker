import hashlib
import secrets
import time
from datetime import datetime, timedelta, timezone
from jose import jwt, JWTError
from passlib.context import CryptContext
from fastapi import HTTPException, Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from .config import settings
from .database import get_db
from .models import RefreshSession, User

passwords = CryptContext(schemes=['bcrypt'], deprecated='auto')
bearer = HTTPBearer(auto_error=False)
def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()

def tokens(db, user: User):
    cfg = settings()
    access = jwt.encode({'sub': user.id, 'type': 'access', 'aud': cfg.app_env, 'iss': 'expense-tracker',
                         'exp': datetime.now(timezone.utc) + timedelta(minutes=cfg.access_token_expire_minutes)}, cfg.jwt_secret_key, algorithm='HS256')
    refresh = secrets.token_urlsafe(48)
    db.add(RefreshSession(token_hash=digest(refresh), user_id=user.id,
                          expires_at=int(time.time()) + cfg.refresh_token_expire_days * 86400))
    return {'access_token': access, 'refresh_token': refresh, 'token_type': 'bearer', 'user_id': user.id}

def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db=Depends(get_db)):
    if not credentials:
        raise HTTPException(401, 'Authentication required')
    try:
        payload = jwt.decode(credentials.credentials, settings().jwt_secret_key, algorithms=['HS256'], audience=settings().app_env, issuer='expense-tracker')
        if payload.get('type') != 'access':
            raise ValueError()
        user = db.get(User, payload['sub'])
        if user is None:
            raise ValueError()
        return user
    except (JWTError, KeyError, ValueError):
        raise HTTPException(401, 'Invalid or expired token') from None
