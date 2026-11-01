"""Track and clean only synthetic device-test accounts created after a local snapshot."""
import json
import re
import sys
from pathlib import Path
from sqlalchemy import select,delete
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app.database import Session
from app.models import (User,FinancialRecord,RefreshSession,LoginAttempt,SplitShare,
                        SplitMember,SplitGroup,Budget,Transaction,BankAccount,Category,SyncLog)
from app.security import passwords,digest
from app.config import settings

snapshot=Path(__file__).resolve().parents[2]/'android/build/device-smoke-users.json'
try:
    if settings().app_env!='dev':raise ValueError('Device smoke tests are restricted to dev')
    with Session() as db:
        if sys.argv[1:]==['capture']:
            if snapshot.exists():raise ValueError('A previous snapshot already exists')
            snapshot.parent.mkdir(parents=True,exist_ok=True)
            snapshot.write_text(json.dumps(list(db.scalars(select(User.id)))),encoding='utf-8')
            print('Captured pre-test user IDs; no account details were printed.')
        elif sys.argv[1:]==['cleanup']:
            previous=set(json.loads(snapshot.read_text(encoding='utf-8')))
            count=0
            for user in db.scalars(select(User)).all():
                if user.id in previous:continue
                if not re.fullmatch(r'device-[0-9a-f-]{36}@example\.com',user.email):continue
                if not passwords.verify('device-smoke-test-password',user.password_hash):continue
                for model in (SplitShare,SplitMember,SplitGroup,Budget,Transaction,BankAccount,Category,SyncLog,FinancialRecord,RefreshSession):
                    db.execute(delete(model).where(model.user_id==user.id))
                db.execute(delete(LoginAttempt).where(LoginAttempt.key==digest(user.email)))
                db.delete(user)
                count+=1
            db.commit()
            snapshot.unlink()
            print(f'Cleaned {count} newly created synthetic device-test accounts; pre-existing users were retained.')
        else:raise ValueError('Use capture or cleanup')
except Exception:
    raise SystemExit('Device smoke-account tracking failed; credentials were not printed.') from None
