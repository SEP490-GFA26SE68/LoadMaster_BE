
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    DATABASE_URL: str

    APP_NAME: str = "OptimizeService"
    APP_ENV: str = "development"
    APP_PORT: int = 8000

    # Keycloak JWT Resource Server
    KEYCLOAK_ISSUER: str = "http://localhost:8180/realms/loadmaster"
    KEYCLOAK_AUDIENCE: str = "optimization-service"
    KEYCLOAK_ALLOWED_CLIENT_ID: str = "loadmaster-backend-service"
    KEYCLOAK_REQUIRED_ROLE: str = "OPTIMIZATION_EXECUTE"
    KEYCLOAK_JWKS_URL: Optional[str] = None

    # Keycloak Client Credentials
    # Python lấy access token để gọi Spring Boot
    KEYCLOAK_AUTH_URL: str = "http://localhost:8180"
    KEYCLOAK_REALM: str = "loadmaster"
    KEYCLOAK_CLIENT_ID: str = "optimization-service"
    KEYCLOAK_CLIENT_SECRET: str

    OPTIMIZATION_ENGINE_URL: Optional[str] = None
    OPTIMIZATION_ENGINE_TIMEOUT_SEC: int = 60

    LOADMASTER_SERVICE_URL: str = "http://localhost:8080"
    REDIS_URL: str = "redis://localhost:6379"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
