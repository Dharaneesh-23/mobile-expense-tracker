from sqlalchemy import create_engine, MetaData
from sqlalchemy.orm import DeclarativeBase, sessionmaker
from .config import settings

class Base(DeclarativeBase):
    metadata = MetaData(schema='app_schema')

def make_engine(url: str, schema: str):
    options = {'schema_translate_map': {'app_schema': None if url.startswith('sqlite') else schema}}
    kwargs = {'connect_args': {'check_same_thread': False}} if url.startswith('sqlite') else {'pool_size': 3, 'max_overflow': 2, 'pool_recycle': 300, 'connect_args': {'connect_timeout': 10}}
    return create_engine(url, execution_options=options, pool_pre_ping=True, **kwargs)

engine = make_engine(settings().database_url, settings().db_schema)
Session = sessionmaker(engine, expire_on_commit=False)

def get_db():
    with Session() as session:
        yield session
