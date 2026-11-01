from sqlalchemy import String, Text, BigInteger, Integer, Boolean, ForeignKeyConstraint, JSON
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
