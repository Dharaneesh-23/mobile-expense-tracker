"""Synthetic API smoke test against the configured database, followed by exact-ID cleanup."""
import sys
import time
import uuid
import secrets
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from sqlalchemy import delete
from app.main import app
from app.database import Session
from app.models import User, RefreshSession, FinancialRecord, LoginAttempt
from app.security import digest
from app.config import settings

def main():
    if settings().app_env not in ('dev','qa'): raise SystemExit('Testing is restricted to dev/qa.')
    accounts=[]
    emails=[]
    try:
        with TestClient(app) as client:
            for _ in range(2):
                email=f'smoke-{uuid.uuid4()}@example.com';emails.append(email)
                result=client.post('/api/v1/auth/register',json={'email':email,'password':secrets.token_urlsafe(24)})
                if result.status_code!=201:raise RuntimeError('Registration failed')
                accounts.append(result.json())
            a,b=accounts
            headers={'Authorization':'Bearer '+a['access_token']}
            now=int(time.time()*1000)
            record={'id':str(uuid.uuid4()),'kind':'transaction','updated_at':now,'data':{'amount':'25.00','direction':'debit','payee':'Synthetic smoke test','occurred_at':now}}
            body={'device_id':'live-smoke','cursor':0,'changes':[record]}
            result=client.post('/api/v1/sync',headers=headers,json=body)
            if result.status_code!=200 or result.json()['cursor']!=1:raise RuntimeError('Write failed')
            if client.post('/api/v1/sync',headers=headers,json=body).json()['cursor']!=1:raise RuntimeError('Idempotency failed')
            if client.get('/api/v1/transactions',headers={'Authorization':'Bearer '+b['access_token']}).json()['total']!=0:raise RuntimeError('User isolation failed')
            pdf=client.post('/api/v1/reports/pdf',headers=headers,json={})
            if not pdf.content.startswith(b'%PDF'):raise RuntimeError('Report failed')
            print(f'{settings().app_env}: live PostgreSQL authentication, sync, idempotency, user isolation and PDF smoke checks passed.')
    finally:
        with Session() as db:
            for account in accounts:
                uid=account['user_id']
                db.execute(delete(FinancialRecord).where(FinancialRecord.user_id==uid))
                db.execute(delete(RefreshSession).where(RefreshSession.user_id==uid))
                db.execute(delete(User).where(User.id==uid))
            for email in emails:db.execute(delete(LoginAttempt).where(LoginAttempt.key==digest(email)))
            db.commit()
        print('Synthetic smoke-test records and accounts cleaned up.')

if __name__=='__main__':
    try: main()
    except Exception: raise SystemExit('Live smoke check failed; credentials were not printed.') from None
