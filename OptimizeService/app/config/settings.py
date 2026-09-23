from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql://postgres:postgres@localhost:5432/loadmaster"
    APP_NAME: str = "OptimizeService"
    APP_ENV: str = "development"
    APP_PORT: int = 8000

    KEYCLOAK_AUTH_URL: str = "http://localhost:8080"
    KEYCLOAK_REALM: str = "loadmaster"
    KEYCLOAK_CLIENT_ID: str = "optimize-service"
    KEYCLOAK_CLIENT_SECRET: str = "secret"

    OPTIMIZATION_ENGINE_URL: Optional[str] = None
    OPTIMIZATION_ENGINE_TIMEOUT_SEC: int = 60

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


settings = Settings()
