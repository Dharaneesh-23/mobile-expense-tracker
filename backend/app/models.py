from datetime import date, datetime
from decimal import Decimal
from sqlalchemy import (String, Text, BigInteger, Integer, Boolean, ForeignKeyConstraint,
                        ForeignKey, UniqueConstraint, CheckConstraint, JSON, Uuid,
                        DateTime, Date, Numeric, func, false, Index, text)
from sqlalchemy.orm import Mapped, mapped_column
from .database import Base

class User(Base):
    __tablename__ = 'users'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    password_hash: Mapped[str] = mapped_column(Text)
    revision: Mapped[int] = mapped_column(BigInteger, default=0)

class RefreshSession(Base):
    __tablename__ = 'refresh_sessions'
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), index=True)
    expires_at: Mapped[int] = mapped_column(BigInteger)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)

class FinancialRecord(Base):
    __tablename__ = 'records'
    user_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    kind: Mapped[str] = mapped_column(String(24))
    data: Mapped[dict] = mapped_column(JSON)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    revision: Mapped[int] = mapped_column(BigInteger, index=True)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)

class LoginAttempt(Base):
    __tablename__ = 'login_attempts'
    key: Mapped[str] = mapped_column(String(64), primary_key=True)
    window: Mapped[int] = mapped_column(BigInteger)
    count: Mapped[int] = mapped_column(Integer)

class EntityFields:
    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey('app_schema.users.id'))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
    deleted: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())

class BankAccount(EntityFields, Base):
    __tablename__ = 'bank_accounts'
    __table_args__ = (UniqueConstraint('user_id','id'), UniqueConstraint('user_id','bank_name','account_last4'))
    bank_name: Mapped[str] = mapped_column(Text)
    account_last4: Mapped[str] = mapped_column(String(4))

class Category(EntityFields, Base):
    __tablename__ = 'categories'
    user_id: Mapped[str | None] = mapped_column(String(36), ForeignKey('app_schema.users.id'), nullable=True)
    name: Mapped[str] = mapped_column(Text)
    is_system: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())

class Transaction(EntityFields, Base):
    __tablename__ = 'transactions'
    __table_args__ = (
        UniqueConstraint('user_id','id'),
        UniqueConstraint('user_id','sync_record_id',name='uq_transactions_sync_record'),
        ForeignKeyConstraint(['user_id','bank_account_id'],['app_schema.bank_accounts.user_id','app_schema.bank_accounts.id']),
        ForeignKeyConstraint(['user_id','duplicate_of'],['app_schema.transactions.user_id','app_schema.transactions.id']),
        CheckConstraint("direction IN ('credit','debit')"), CheckConstraint('amount > 0'),
    )
    sync_record_id: Mapped[str | None] = mapped_column(String(36))
    bank_account_id: Mapped[str | None] = mapped_column(Uuid(as_uuid=False))
    category_id: Mapped[str | None] = mapped_column(Uuid(as_uuid=False), ForeignKey('app_schema.categories.id'))
    source_type: Mapped[str] = mapped_column(Text)
    source_app: Mapped[str | None] = mapped_column(Text)
    amount: Mapped[Decimal] = mapped_column(Numeric(12,2))
    direction: Mapped[str] = mapped_column(Text)
    payee_name: Mapped[str] = mapped_column(Text)
    raw_text: Mapped[str | None] = mapped_column(Text)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    duplicate_of: Mapped[str | None] = mapped_column(Uuid(as_uuid=False))
    note: Mapped[str | None] = mapped_column(Text)
    reference: Mapped[str | None] = mapped_column(Text)

class Budget(EntityFields, Base):
    __tablename__ = 'budgets'
    __table_args__ = (
        UniqueConstraint('user_id','sync_record_id',name='uq_budgets_sync_record'),
        Index('uq_budgets_active_period','user_id','category_id','period','start_date',unique=True,
              postgresql_where=text('NOT deleted'),sqlite_where=text('deleted = 0')),
        CheckConstraint('limit_amount > 0'),
    )
    sync_record_id: Mapped[str | None] = mapped_column(String(36))
    category_id: Mapped[str] = mapped_column(Uuid(as_uuid=False), ForeignKey('app_schema.categories.id'))
    limit_amount: Mapped[Decimal] = mapped_column(Numeric(12,2))
    period: Mapped[str] = mapped_column(Text)
    start_date: Mapped[date] = mapped_column(Date)
    end_date: Mapped[date | None] = mapped_column(Date)

class SplitGroup(EntityFields, Base):
    __tablename__ = 'split_groups'
    __table_args__ = (UniqueConstraint('user_id','id'),)
    name: Mapped[str] = mapped_column(Text)
    source: Mapped[str] = mapped_column(Text)

class SplitMember(EntityFields, Base):
    __tablename__ = 'split_members'
    __table_args__ = (
        UniqueConstraint('user_id','group_id','id'),
        ForeignKeyConstraint(['user_id','group_id'],['app_schema.split_groups.user_id','app_schema.split_groups.id']),
    )
    group_id: Mapped[str] = mapped_column(Uuid(as_uuid=False))
    name: Mapped[str] = mapped_column(Text)
    phone: Mapped[str | None] = mapped_column(Text)

class SplitShare(EntityFields, Base):
    __tablename__ = 'split_shares'
    __table_args__ = (
        UniqueConstraint('user_id','sync_record_id',name='uq_split_shares_sync_record'),
        ForeignKeyConstraint(['user_id','group_id'],['app_schema.split_groups.user_id','app_schema.split_groups.id']),
        ForeignKeyConstraint(['user_id','group_id','member_id'],['app_schema.split_members.user_id','app_schema.split_members.group_id','app_schema.split_members.id']),
        ForeignKeyConstraint(['user_id','linked_transaction_id'],['app_schema.transactions.user_id','app_schema.transactions.id']),
        ForeignKeyConstraint(['user_id','settled_transaction_id'],['app_schema.transactions.user_id','app_schema.transactions.id']),
        CheckConstraint('amount > 0'),
    )
    sync_record_id: Mapped[str | None] = mapped_column(String(36))
    group_id: Mapped[str] = mapped_column(Uuid(as_uuid=False))
    member_id: Mapped[str] = mapped_column(Uuid(as_uuid=False))
    linked_transaction_id: Mapped[str | None] = mapped_column(Uuid(as_uuid=False))
    amount: Mapped[Decimal] = mapped_column(Numeric(12,2))
    direction: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(Text)
    settled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    settled_transaction_id: Mapped[str | None] = mapped_column(Uuid(as_uuid=False))

class SyncLog(Base):
    __tablename__ = 'sync_log'
    __table_args__ = (UniqueConstraint('user_id','device_id'),)
    id: Mapped[str] = mapped_column(Uuid(as_uuid=False), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey('app_schema.users.id'))
    device_id: Mapped[str] = mapped_column(Text)
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True),server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True),server_default=func.now(),onupdate=func.now())
