"""Disposable localhost API used only for the explicitly requested device smoke test."""
import os
import secrets
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ['APP_ENV'] = 'dev'
os.environ['DB_SCHEMA'] = 'dev'
os.environ['DATABASE_URL'] = 'sqlite:///./device-smoke.db'
os.environ['JWT_SECRET_KEY'] = secrets.token_urlsafe(48)
os.environ['ALLOW_REGISTRATION'] = 'true'
from app.database import Base, engine
from app import models
from app.main import app
import uvicorn
Base.metadata.create_all(engine)
uvicorn.run(app, host='127.0.0.1', port=8000, access_log=False)
