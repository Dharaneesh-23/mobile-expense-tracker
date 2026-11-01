import copy
import time
import uuid
import pytest
from decimal import Decimal
from sqlalchemy import select,func
from test_api import account,headers,change,sync,client
from app.models import (User,FinancialRecord,Transaction,Budget,Category,BankAccount,
                        SplitGroup,SplitMember,SplitShare,SyncLog)
from app.relational import identity
from scripts.backfill_relational import backfill

def record(kind,data,**extra):
    return {'id':str(uuid.uuid4()),'kind':kind,'updated_at':int(time.time()*1000),'data':data,**extra}

def test_sync_populates_all_relational_entities_and_keeps_android_ids(client):
    user=account(client)
    transaction=change()
    transaction['data'].update(bank='HDFC Bank',account_last4='1234')
    budget=record('budget',{'amount':'100.00','category':'Food','period':'monthly'})
    split=record('split',{'amount':'12.50','direction':'they_owe_you','status':'pending',
                         'group':'Trip','payee':'Sample Friend','linked_transaction_id':transaction['id']})
    response=sync(client,user,[split,budget,transaction])
    assert response.status_code==200,response.text
    assert {r['id'] for r in response.json()['server_changes']}=={transaction['id'],budget['id'],split['id']}
    with client.test_sessions() as db:
        for model in (Transaction,Budget,Category,BankAccount,SplitGroup,SplitMember,SplitShare,SyncLog):
            assert db.scalar(select(func.count()).select_from(model))==1
        tx=db.scalar(select(Transaction))
        share=db.scalar(select(SplitShare))
        assert tx.amount==Decimal('25.00') and tx.sync_record_id==transaction['id']
        assert share.linked_transaction_id==tx.id
        assert db.scalar(select(SyncLog)).device_id=='test-device'
    item=client.get('/api/v1/transactions',headers=headers(user)).json()['items'][0]
    assert item['id']==transaction['id'] and item['bank']=='HDFC Bank' and item['category']=='Food'

def test_cross_user_relational_reference_rolls_back_entire_sync(client):
    a,b=account(client),account(client,'two')
    first=change();first['data'].update(bank='HDFC Bank',account_last4='1234')
    assert sync(client,a,[first]).status_code==200
    with client.test_sessions() as db:bank=db.scalar(select(BankAccount))
    invalid=change();invalid['data']['bank_account_id']=bank.id
    response=sync(client,b,[invalid])
    assert response.status_code==422
    with client.test_sessions() as db:
        assert db.get(User,b['user_id']).revision==0
        assert db.get(FinancialRecord,(b['user_id'],invalid['id'])) is None
        assert db.get(Transaction,identity(b['user_id'],'transaction',invalid['id'])) is None

def test_deleted_budget_can_be_replaced_without_destroying_history(client):
    user=account(client)
    first=record('budget',{'amount':'100.00','category':'Food','period':'monthly'})
    assert sync(client,user,[first]).status_code==200
    tombstone=copy.deepcopy(first);tombstone.update(deleted=True,updated_at=first['updated_at']+1)
    assert sync(client,user,[tombstone]).status_code==200
    second=record('budget',{'amount':'200.00','category':'Food','period':'monthly'})
    assert sync(client,user,[second]).status_code==200
    with client.test_sessions() as db:
        assert db.scalar(select(func.count()).select_from(Budget))==2
        assert db.scalar(select(func.count()).select_from(Budget).where(Budget.deleted.is_(False)))==1

def test_late_arriving_linked_transaction_is_reconciled(client):
    user=account(client)
    transaction=change()
    split=record('split',{'amount':'5.00','direction':'you_owe_them','status':'pending',
                         'group':'Test','payee':'Friend','linked_transaction_id':transaction['id']})
    assert sync(client,user,[split]).status_code==200
    with client.test_sessions() as db: assert db.scalar(select(SplitShare)).linked_transaction_id is None
    assert sync(client,user,[transaction]).status_code==200
    with client.test_sessions() as db:
        assert db.scalar(select(SplitShare)).linked_transaction_id==db.scalar(select(Transaction)).id

def test_backfill_is_idempotent_and_preserves_legacy_payload(client):
    user=account(client)
    source=change();source['data']['unknown_future_field']={'keep':'verbatim'}
    original=copy.deepcopy(source['data'])
    with client.test_sessions() as db:
        legacy=FinancialRecord(user_id=user['user_id'],id=source['id'],kind='transaction',data=original,
                               updated_at=source['updated_at'],revision=1,deleted=False)
        db.add(legacy);db.get(User,user['user_id']).revision=1;db.commit()
        assert backfill(db)['transaction']==1
        db.commit()
        assert backfill(db)['transaction']==1
        db.commit()
        assert db.scalar(select(func.count()).select_from(Transaction))==1
        preserved=db.get(FinancialRecord,(user['user_id'],source['id']))
        assert preserved.data==original and preserved.revision==1 and preserved.updated_at==source['updated_at']

def test_transaction_list_reads_relational_values(client):
    user=account(client)
    assert sync(client,user,[change()]).status_code==200
    with client.test_sessions() as db:
        row=db.scalar(select(Transaction));row.payee_name='Canonical merchant';db.commit()
    result=client.get('/api/v1/transactions?payee=Canonical',headers=headers(user)).json()
    assert result['total']==1 and result['items'][0]['payee']=='Canonical merchant'

def test_invalid_legacy_payload_rolls_back_without_losing_journal(client):
    user=account(client)
    valid=change()
    with client.test_sessions() as db:
        db.add(FinancialRecord(user_id=user['user_id'],id=valid['id'],kind='transaction',
            data=valid['data'],updated_at=valid['updated_at'],revision=1,deleted=False))
        db.add(FinancialRecord(user_id=user['user_id'],id=str(uuid.uuid4()),kind='budget',
            data={'amount':'-1','category':'Food'},updated_at=valid['updated_at'],revision=2,deleted=False))
        db.commit()
        with pytest.raises(ValueError):backfill(db)
        db.rollback()
        assert db.scalar(select(func.count()).select_from(Transaction))==0
        assert db.scalar(select(func.count()).select_from(FinancialRecord))==2
