"""Atomic relational projection of the retained Android sync journal.

Journal IDs remain unchanged. Physical IDs are namespaced by owner so a client's
UUID cannot overwrite another user's row. No raw journal payload is discarded.
"""
from datetime import date, datetime, timezone
from decimal import Decimal
from uuid import UUID, uuid5, NAMESPACE_URL
from zoneinfo import ZoneInfo
from sqlalchemy import select, func, or_
from .models import (BankAccount, Category, Transaction, Budget, SplitGroup,
                     SplitMember, SplitShare, SyncLog, FinancialRecord)

KINDS = {'transaction':Transaction, 'budget':Budget, 'split':SplitShare}
IST = ZoneInfo('Asia/Kolkata')

def identity(user_id, kind, key):
    return str(uuid5(NAMESPACE_URL, f'expense-tracker:{user_id}:{kind}:{key}'))

def timestamp(ms):
    return datetime.fromtimestamp(int(ms)/1000, timezone.utc)

def milliseconds(value):
    if value.tzinfo is None: value = value.replace(tzinfo=timezone.utc)
    return int(value.timestamp()*1000)

def owned(db, model, user_id, entity_id):
    try: entity_id = str(UUID(str(entity_id)))
    except (ValueError, TypeError, AttributeError): raise ValueError('Invalid related entity ID') from None
    row = db.get(model, entity_id)
    if row is None or row.user_id not in (user_id, None) or row.deleted:
        raise ValueError('Related entity is not available to this user')
    if row.user_id is None and model is not Category:
        raise ValueError('Related entity is not available to this user')
    return row

def category_for(db, user_id, data):
    if data.get('category_id'):
        return owned(db,Category,user_id,data['category_id'])
    name = str(data.get('category') or 'Uncategorized').strip()
    if not name or len(name)>100: raise ValueError('Category name must be 1–100 characters')
    row = db.scalar(select(Category).where(
        func.lower(Category.name)==name.lower(),
        or_(Category.user_id==user_id, (Category.user_id.is_(None) & Category.is_system.is_(True)))
    ).order_by(Category.is_system))
    if row is None:
        row=Category(id=identity(user_id,'category',name.lower()),user_id=user_id,name=name,is_system=False)
        db.add(row)
    row.deleted=False
    db.flush()
    return row

def bank_for(db,user_id,data):
    if data.get('bank_account_id'): return owned(db,BankAccount,user_id,data['bank_account_id'])
    name=str(data.get('bank') or '').strip()
    last=str(data.get('account_last4') or '').strip()
    if not name or len(last)!=4 or not last.isascii() or not last.isdigit(): return None
    if len(name)>150: raise ValueError('Bank name is too long')
    row=db.scalar(select(BankAccount).where(BankAccount.user_id==user_id,func.lower(BankAccount.bank_name)==name.lower(),BankAccount.account_last4==last))
    if row is None:
        row=BankAccount(id=identity(user_id,'bank',name.lower()+':'+last),user_id=user_id,bank_name=name,account_last4=last)
        db.add(row)
    row.deleted=False
    db.flush()
    return row

def group_for(db,user_id,data):
    if data.get('group_id'): return owned(db,SplitGroup,user_id,data['group_id'])
    name=str(data.get('group') or '').strip()
    if not name or len(name)>200: raise ValueError('Group name must be 1–200 characters')
    row=db.scalar(select(SplitGroup).where(SplitGroup.user_id==user_id,func.lower(SplitGroup.name)==name.lower()))
    if row is None:
        row=SplitGroup(id=identity(user_id,'group',name.lower()),user_id=user_id,name=name,
                       source='gpay_split' if data.get('source')=='gpay' else 'app_native')
        db.add(row)
    row.deleted=False
    db.flush()
    return row

def member_for(db,user_id,group,data):
    if data.get('member_id'):
        member=owned(db,SplitMember,user_id,data['member_id'])
        if member.group_id!=group.id: raise ValueError('Member belongs to another group')
        return member
    name=str(data.get('payee') or '').strip()
    if not name or len(name)>200: raise ValueError('Person name must be 1–200 characters')
    row=db.scalar(select(SplitMember).where(SplitMember.user_id==user_id,SplitMember.group_id==group.id,func.lower(SplitMember.name)==name.lower()))
    if row is None:
        row=SplitMember(id=identity(user_id,'member',group.id+':'+name.lower()),user_id=user_id,group_id=group.id,name=name)
        db.add(row)
    row.deleted=False
    db.flush()
    return row

def linked_transaction(db,user_id,client_id):
    if not client_id: return None
    row=db.scalar(select(Transaction).where(Transaction.user_id==user_id,Transaction.sync_record_id==str(client_id)))
    return row.id if row and not row.deleted else None

def project(db, record):
    model=KINDS.get(record.kind)
    if model is None: raise ValueError('Unknown financial record kind')
    entity_id=identity(record.user_id,record.kind,record.id)
    row=db.get(model,entity_id)
    if record.deleted:
        if row is not None: row.deleted=True
        return row
    d=record.data
    if row is None:
        row=model(id=entity_id,user_id=record.user_id,sync_record_id=record.id)
        # Flush dependencies before placing an incomplete row in the session.
    if record.kind=='transaction':
        category=category_for(db,record.user_id,d)
        bank=bank_for(db,record.user_id,d)
        row.category_id=category.id
        row.bank_account_id=bank.id if bank else None
        row.amount=Decimal(d['amount'])
        row.direction=d['direction']
        row.payee_name=str(d['payee']).strip()
        row.occurred_at=timestamp(d['occurred_at'])
        row.note=d.get('note') or None
        row.reference=d.get('reference') or None
        row.raw_text=d.get('raw_text') or None
        source=d.get('source','manual')
        row.source_type={'gpay':'notification','sms':'sms','manual':'manual','import':'import_statement'}.get(source,'manual')
        if str(d.get('event_id','')).startswith('takeout:'): row.source_type='import_takeout'
        row.source_app={'gpay':'gpay','sms':'bank_sms','manual':'manual'}.get(source)
    elif record.kind=='budget':
        row.category_id=category_for(db,record.user_id,d).id
        row.limit_amount=Decimal(d['amount'])
        row.period=d.get('period','monthly')
        if row.period not in ('monthly','weekly','custom'): raise ValueError('Invalid budget period')
        default_start=timestamp(record.updated_at).astimezone(IST).date().replace(day=1)
        row.start_date=date.fromisoformat(d['start_date']) if d.get('start_date') else (row.start_date or default_start)
        row.end_date=date.fromisoformat(d['end_date']) if d.get('end_date') else None
        if row.end_date and row.end_date<row.start_date: raise ValueError('Budget end precedes start')
        if row.period=='custom' and row.end_date is None: raise ValueError('Custom budget requires end_date')
        conflict=db.scalar(select(Budget).where(Budget.user_id==record.user_id,Budget.category_id==row.category_id,
                     Budget.period==row.period,Budget.start_date==row.start_date,Budget.id!=row.id,Budget.deleted.is_(False)))
        if conflict is not None: raise ValueError('A budget already exists for this category and period')
    else:
        group=group_for(db,record.user_id,d)
        member=member_for(db,record.user_id,group,d)
        row.group_id=group.id
        row.member_id=member.id
        row.amount=Decimal(d['amount'])
        row.direction=d['direction']
        row.status=d['status']
        row.settled_at=(row.settled_at or timestamp(record.updated_at)) if row.status=='settled' else None
        row.linked_transaction_id=linked_transaction(db,record.user_id,d.get('linked_transaction_id'))
        row.settled_transaction_id=linked_transaction(db,record.user_id,d.get('settled_transaction_id'))
    row.deleted=False
    db.add(row)
    db.flush()
    return row

def reconcile_split_links(db,user_id):
    for row in db.scalars(select(SplitShare).where(SplitShare.user_id==user_id,SplitShare.deleted.is_(False))):
        record=db.get(FinancialRecord,(user_id,row.sync_record_id)) if row.sync_record_id else None
        if record:
            row.linked_transaction_id=linked_transaction(db,user_id,record.data.get('linked_transaction_id'))
            row.settled_transaction_id=linked_transaction(db,user_id,record.data.get('settled_transaction_id'))

def touch_sync_log(db,user_id,device_id):
    row=db.scalar(select(SyncLog).where(SyncLog.user_id==user_id,SyncLog.device_id==device_id))
    if row is None:
        row=SyncLog(id=identity(user_id,'device',device_id),user_id=user_id,device_id=device_id)
        db.add(row)
    row.last_synced_at=datetime.now(timezone.utc)

def transaction_data(db,row):
    journal=db.get(FinancialRecord,(row.user_id,row.sync_record_id)) if row.sync_record_id else None
    data=dict(journal.data) if journal else {}
    data.update(id=row.sync_record_id or row.id,kind='transaction',amount=f'{row.amount:.2f}',direction=row.direction,
                payee=row.payee_name,occurred_at=milliseconds(row.occurred_at),note=row.note or '',
                reference=row.reference or '',category_id=row.category_id,bank_account_id=row.bank_account_id)
    category=db.get(Category,row.category_id) if row.category_id else None
    bank=db.get(BankAccount,row.bank_account_id) if row.bank_account_id else None
    data['category']=category.name if category else 'Uncategorized'
    if bank: data.update(bank=bank.bank_name,account_last4=bank.account_last4)
    return data

def transaction_items(db,user_id):
    return [transaction_data(db,row) for row in db.scalars(select(Transaction).where(Transaction.user_id==user_id,Transaction.deleted.is_(False)))]
