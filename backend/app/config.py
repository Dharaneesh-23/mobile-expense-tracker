from functools import lru_cache
from pathlib import Path
from typing import Literal
from pydantic import model_validator,SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=Path(__file__).resolve().parents[1] / '.env', extra='ignore', hide_input_in_errors=True)
    app_env: Literal['dev', 'qa'] = 'dev'
    db_schema: Literal['dev', 'qa'] = 'dev'
    database_url: str = 'sqlite:///./development.db'
    migration_database_url: str | None = None
    jwt_secret_key: str
    allow_registration: bool = False
    access_token_expire_minutes: int = 15
    refresh_token_expire_days: int = 30
    updates_github_repository: str = 'Dharaneesh-23/mobile-expense-tracker'
    updates_github_token: SecretStr = SecretStr('')

    @model_validator(mode='after')
    def validate_environment(self):
        if self.app_env != self.db_schema:
            raise ValueError('APP_ENV and DB_SCHEMA must match; prod is disabled during development')
        if len(self.jwt_secret_key) < 32 or self.jwt_secret_key.startswith('REPLACE'):
            raise ValueError('Configure a random JWT_SECRET_KEY of at least 32 characters')
        if self.app_env == 'qa' and self.database_url.startswith('sqlite'):
            raise ValueError('QA requires PostgreSQL')
        return self

@lru_cache
def settings():
    return Settings()
