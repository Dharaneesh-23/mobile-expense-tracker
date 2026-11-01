"""Project retained sync records into relational tables, atomically per environment.

Default is a rollback-only dry run. --apply commits without removing journal data.
"""
import hashlib
import json
import sys
from pathlib import Path
from sqlalchemy import select
from sqlalchemy.orm import Session
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.database import make_engine
from app.models import User, FinancialRecord
from app.schemas import Change
from app.relational import project,reconcile_split_links

root=Path(__file__).resolve().parents[1]

def snapshot(rows):
    payload=[(r.user_id,r.id,r.kind,r.updated_at,r.revision,r.deleted,r.data) for r in rows]
    return hashlib.sha256(json.dumps(payload,sort_keys=True,default=str).encode()).hexdigest()

def backfill(db):
    users=db.scalars(select(User).order_by(User.id).with_for_update()).all()
    records=db.scalars(select(FinancialRecord).order_by(FinancialRecord.user_id,FinancialRecord.id)).all()
    original=snapshot(records)
    counts={kind:0 for kind in ('transaction','budget','split')}
    if any(record.kind not in counts for record in records):
        raise ValueError('Unsupported legacy record kind requires review')
    for kind in counts:
        for record in records:
            if record.kind!=kind:continue
            # Validate legacy payloads before writing anything. Never silently skip invalid rows.
            Change(id=record.id,kind=record.kind,updated_at=record.updated_at,deleted=record.deleted,data=dict(record.data))
            project(db,record)
            counts[kind]+=1
    for user in users:reconcile_split_links(db,user.id)
    db.flush()
    if snapshot(records)!=original:raise ValueError('Journal preservation check failed')
    return counts

if __name__=='__main__':
    if sys.argv[1:] not in ([],['--apply']):raise SystemExit('Usage: backfill_relational.py [--apply]')
    try:
        for env in ('dev','qa'):
            cfg=Settings(_env_file=root/f'.env.{env}')
            engine=make_engine(cfg.database_url,cfg.db_schema)
            with Session(engine,expire_on_commit=False) as db:
                counts=backfill(db)
                if '--apply' in sys.argv:db.commit()
                else:db.rollback()
                print(f'{env}: {counts}; journal preserved; '+('committed' if '--apply' in sys.argv else 'dry run rolled back'))
            engine.dispose()
    except Exception:
        raise SystemExit('Backfill failed and its environment transaction rolled back. No financial details or credentials were printed.') from None
