"""Initial users, refresh sessions, versioned records, and auth rate limits.

Revision ID: 0001
"""
from alembic import op
from app.database import Base
from app import models

revision = '0001'
down_revision = None
branch_labels = None
depends_on = None

def upgrade():
    Base.metadata.create_all(bind=op.get_bind(), tables=[models.User.__table__, models.RefreshSession.__table__, models.FinancialRecord.__table__, models.LoginAttempt.__table__])

def downgrade():
    Base.metadata.drop_all(bind=op.get_bind(), tables=[models.LoginAttempt.__table__, models.FinancialRecord.__table__, models.RefreshSession.__table__, models.User.__table__])
