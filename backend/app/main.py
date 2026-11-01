import io
import time
import uuid
from decimal import Decimal
from datetime import datetime, timezone
from xml.sax.saxutils import escape
from fastapi import FastAPI, Depends, HTTPException, Request, Response, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import SimpleDocTemplate, Table, TableStyle, Paragraph, Spacer
from .config import settings
from .database import get_db
from .models import User, RefreshSession, FinancialRecord, LoginAttempt
from .schemas import Credentials, RefreshBody, SyncBody
from .security import passwords, tokens, digest, current_user
from . import relational

app = FastAPI(title='Personal Expense Tracker', version='0.2.0')

@app.middleware('http')
async def limit_request(request: Request, call_next):
    # Limit the actual stream as well as the untrusted Content-Length header.
    if request.method in ('POST', 'PUT', 'PATCH'):
        total = 0
        chunks = []
        async for chunk in request.stream():
            total += len(chunk)
            if total > 2_000_000:
                return Response('Request too large', status_code=413)
            chunks.append(chunk)
        request._body = b''.join(chunks)
    response = await call_next(request)
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response

@app.get('/health')
def health():
    return {'status': 'ok', 'environment': settings().app_env, 'storage': 'relational'}

def throttle(db, email):
    key = digest(email.lower())
    row = db.get(LoginAttempt, key)
    window = int(time.time()) // 900
    if row is None:
        row = LoginAttempt(key=key, window=window, count=0)
        db.add(row)
    if row.window != window:
        row.window, row.count = window, 0
    row.count += 1
    db.commit()
    if row.count > 10:
        raise HTTPException(429, 'Too many attempts; retry in 15 minutes')

@app.post('/api/v1/auth/register', status_code=201)
def register(body: Credentials, db=Depends(get_db)):
    if not settings().allow_registration:
        raise HTTPException(403, 'Registration is disabled')
    email = str(body.email).lower()
    throttle(db, email)
    user = User(id=str(uuid.uuid4()), email=email, password_hash=passwords.hash(body.password))
    db.add(user)
    try:
        db.flush()
        result = tokens(db, user)
        db.commit()
        return result
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, 'Account already exists') from None

@app.post('/api/v1/auth/login')
def login(body: Credentials, db=Depends(get_db)):
    email = str(body.email).lower()
    throttle(db, email)
    user = db.scalar(select(User).where(User.email == email))
    if user is None or not passwords.verify(body.password, user.password_hash):
        raise HTTPException(401, 'Invalid credentials')
    result = tokens(db, user)
    db.commit()
    return result

@app.post('/api/v1/auth/refresh')
def refresh(body: RefreshBody, db=Depends(get_db)):
    row = db.scalar(select(RefreshSession).where(RefreshSession.token_hash == digest(body.refresh_token)).with_for_update())
    if row is None or row.revoked or row.expires_at < time.time():
        raise HTTPException(401, 'Invalid refresh token')
    row.revoked = True
    result = tokens(db, db.get(User, row.user_id))
    db.commit()
    return result

@app.post('/api/v1/auth/logout', status_code=204)
def logout(body: RefreshBody, db=Depends(get_db)):
    row = db.get(RefreshSession, digest(body.refresh_token))
    if row:
        row.revoked = True
        db.commit()
    return Response(status_code=204)

def envelope(row):
    return {'id': row.id, 'kind': row.kind, 'data': row.data, 'updated_at': row.updated_at, 'deleted': row.deleted, 'revision': row.revision}

@app.post('/api/v1/sync')
def sync(body: SyncBody, user=Depends(current_user), db=Depends(get_db)):
    # Serializing per user prevents commit-order gaps in the pull cursor.
    locked = db.scalar(select(User).where(User.id == user.id).with_for_update().execution_options(populate_existing=True))
    if body.cursor > locked.revision:
        raise HTTPException(409, 'Invalid cursor; reset sync cursor')
    changed=[]
    for change in body.changes:
        if change.updated_at > int(time.time() * 1000) + 300000:
            raise HTTPException(422, 'Device clock is too far ahead')
        row = db.get(FinancialRecord, (user.id, str(change.id)))
        if row and row.updated_at >= change.updated_at:
            continue
        if row and row.kind != change.kind:
            raise HTTPException(409, 'Record kind cannot change')
        if row is None:
            row = FinancialRecord(user_id=user.id, id=str(change.id), kind=change.kind)
            db.add(row)
        locked.revision += 1
        row.data = dict(change.data, id=str(change.id), kind=change.kind)
        row.updated_at, row.deleted, row.revision = change.updated_at, change.deleted, locked.revision
        changed.append(row)
    db.flush()
    try:
        for kind in ('transaction','budget','split'):
            for row in changed:
                if row.kind==kind: relational.project(db,row)
        relational.reconcile_split_links(db,user.id)
        relational.touch_sync_log(db,user.id,body.device_id)
        db.flush()
    except ValueError as exc:
        db.rollback()
        raise HTTPException(422,str(exc)) from None
    except IntegrityError:
        db.rollback()
        raise HTTPException(409,'Related record conflicts with an existing account, budget, or split') from None
    rows = db.scalars(select(FinancialRecord).where(FinancialRecord.user_id == user.id, FinancialRecord.revision > body.cursor).order_by(FinancialRecord.revision)).all()
    result = {'server_changes': [envelope(r) for r in rows], 'cursor': locked.revision}
    db.commit()
    return result

@app.get('/api/v1/transactions')
def transactions(direction: str | None = None, payee: str = '', bank: str = '', category: str = '', source: str = '',
                 date_from: int | None = None, date_to: int | None = None, amount_min: Decimal | None = None,
                 amount_max: Decimal | None = None, page: int = Query(1, ge=1), limit: int = Query(50, ge=1, le=200),
                 user=Depends(current_user), db=Depends(get_db)):
    items = []
    for d in relational.transaction_items(db,user.id):
        if direction and d.get('direction') != direction: continue
        if payee.lower() not in d.get('payee', '').lower() or bank.lower() not in d.get('bank', '').lower(): continue
        if category and d.get('category') != category: continue
        if source and d.get('source') != source: continue
        if date_from is not None and d['occurred_at'] < date_from: continue
        if date_to is not None and d['occurred_at'] > date_to: continue
        if amount_min is not None and Decimal(d['amount']) < amount_min: continue
        if amount_max is not None and Decimal(d['amount']) > amount_max: continue
        items.append(d)
    items.sort(key=lambda d: d['occurred_at'], reverse=True)
    return {'items': items[(page-1)*limit:page*limit], 'total': len(items), 'page': page}

@app.post('/api/v1/reports/pdf')
def report(payload: dict, user=Depends(current_user), db=Depends(get_db)):
    try:
        start, end = int(payload.get('date_from', 0)), int(payload.get('date_to', 2**63-1))
        if start > end: raise ValueError()
    except (ValueError, TypeError):
        raise HTTPException(422, 'Use ordered millisecond timestamps') from None
    rows = relational.transaction_items(db,user.id)
    table = [['Date (UTC)', 'Payee', 'Direction', 'Amount (INR)']]
    styles = getSampleStyleSheet()
    debit = credit = Decimal('0')
    for d in sorted(rows, key=lambda d: d['occurred_at']):
        if not start <= d['occurred_at'] <= end: continue
        value = Decimal(d['amount'])
        if d['direction'] == 'debit': debit += value
        else: credit += value
        table.append([datetime.fromtimestamp(d['occurred_at']/1000, timezone.utc).strftime('%Y-%m-%d'), Paragraph(escape(d['payee']), styles['BodyText']), d['direction'], str(value)])
    out = io.BytesIO()
    grid = Table(table, repeatRows=1, colWidths=[90, 210, 70, 100])
    grid.setStyle(TableStyle([('BACKGROUND', (0,0), (-1,0), colors.lightgrey), ('VALIGN', (0,0), (-1,-1), 'TOP'), ('GRID', (0,0), (-1,-1), .3, colors.grey)]))
    SimpleDocTemplate(out).build([Paragraph('Expense report', styles['Title']), Paragraph(f'Spent INR {debit} / Received INR {credit}', styles['BodyText']), Spacer(1, 16), grid])
    out.seek(0)
    return StreamingResponse(out, media_type='application/pdf', headers={'Content-Disposition': 'attachment; filename=expenses.pdf'})
