from alembic import context
from sqlalchemy import text
from app.config import settings
from app.database import Base, make_engine
from app import models

cfg = settings()
url = cfg.migration_database_url or cfg.database_url
engine = make_engine(url, cfg.db_schema)
with engine.connect() as connection:
    if not url.startswith('sqlite'):
        # Schema identifiers are constrained to dev/qa by Settings.
        connection.execute(text(f'SET search_path TO "{cfg.db_schema}", public'))
        connection.commit()
    context.configure(connection=connection, target_metadata=Base.metadata,
                      version_table_schema=None if url.startswith('sqlite') else cfg.db_schema,
                      compare_type=True)
    with context.begin_transaction():
        context.run_migrations()
