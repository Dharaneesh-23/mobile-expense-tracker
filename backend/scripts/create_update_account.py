"""Create your personal QA/update login locally without opening public registration."""
import getpass
import sys
import uuid
from pathlib import Path
from sqlalchemy import select
from sqlalchemy.orm import Session
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.database import make_engine
from app.models import User
from app.schemas import Credentials
from app.security import passwords

root=Path(__file__).resolve().parents[1]
try:
    email=input('Personal update account email: ').strip()
    password=getpass.getpass('Password (hidden, 12+ characters): ')
    confirm=getpass.getpass('Confirm password (hidden): ')
    if password!=confirm:raise ValueError('Passwords do not match')
    credentials=Credentials(email=email,password=password)
    cfg=Settings(_env_file=root/'.env.qa')
    engine=make_engine(cfg.database_url,'qa')
    with Session(engine) as db:
        if db.scalar(select(User).where(User.email==str(credentials.email).lower())):
            raise SystemExit('That QA account already exists. Use its existing password; nothing was changed.')
        db.add(User(id=str(uuid.uuid4()),email=str(credentials.email).lower(),password_hash=passwords.hash(password),revision=0))
        db.commit()
    engine.dispose()
    print('Personal QA/update account created. Use it in both apps’ private update settings.')
except SystemExit:raise
except Exception:
    raise SystemExit('Account creation failed. Check the email, password requirements and local QA connection; no password was printed.') from None
