"""Connect relational tables to Android sync identities without removing journal data."""
from pathlib import Path
from alembic import op
import sqlalchemy as sa
from app.config import settings
from app.database import Base
from app import models

revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None

def upgrade():
    bind = op.get_bind()
    postgres = bind.dialect.name == 'postgresql'
    schema = settings().db_schema if postgres else None
    if postgres:
        source = Path(__file__).resolve().parents[2] / 'sql/complete_planned_tables.sql'
        sql = source.read_text(encoding='utf-8')
        sql = sql.replace('\nBEGIN;\n', '\n', 1).rsplit('\nCOMMIT;', 1)[0]
        sql = sql.replace("ARRAY['dev', 'qa']", f"ARRAY['{schema}']")
        bind.connection.driver_connection.execute(sql, prepare=False)
    else:
        Base.metadata.create_all(bind)
    for table in ('transactions','budgets','split_shares'):
        inspector = sa.inspect(bind)
        if 'sync_record_id' not in {c['name'] for c in inspector.get_columns(table,schema=schema)}:
            op.add_column(table, sa.Column('sync_record_id',sa.String(36),nullable=True),schema=schema)
        name = f'uq_{table}_sync_record'
        if name not in {c['name'] for c in sa.inspect(bind).get_unique_constraints(table,schema=schema)}:
            op.create_unique_constraint(name,table,['user_id','sync_record_id'],schema=schema)
    # A deleted monthly limit must not prevent creating a replacement limit.
    if postgres:
        for constraint in sa.inspect(bind).get_unique_constraints('budgets',schema=schema):
            if set(constraint['column_names'])=={'user_id','category_id','period','start_date'}:
                op.drop_constraint(constraint['name'],'budgets',schema=schema,type_='unique')
        if 'uq_budgets_active_period' not in {i['name'] for i in sa.inspect(bind).get_indexes('budgets',schema=schema)}:
            op.create_index('uq_budgets_active_period','budgets',['user_id','category_id','period','start_date'],
                            unique=True,schema=schema,postgresql_where=sa.text('NOT deleted'))

def downgrade():
    raise RuntimeError('Relational sync identity links must be retained; use a reviewed forward migration.')
