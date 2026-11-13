
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    DATABASE_URL: str

    APP_NAME: str = "OptimizeService"
    APP_ENV: str = "development"
    APP_PORT: int = 8000

    # Origin được gọi API (CORS), ngăn bằng dấu phẩy. Cùng tên biến với Spring Boot; vắng thì FRONTEND_URL,
    # vắng nốt thì web chạy ở máy dev.
    CORS_ALLOWED_ORIGINS: Optional[str] = None
    FRONTEND_URL: str = "http://localhost:5173"
    # /docs, /redoc, /openapi.json: vắng thì chỉ bật khi APP_ENV là môi trường dev.
    API_DOCS_ENABLED: Optional[bool] = None

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

    # Trọng số Lumo-R1.1 (PCTPolicy) cho gói ULTIMATE / EP_DBLF_GA_AI. Thiếu file hoặc thiếu torch thì solver dùng heuristic.
    LUMO_MODEL_PATH: str = "app/ml/models/Lumo-R1.1.pt"
    # Ba mô hình Lumo tự xếp cả phương án (gói ULTIMATE): Lumo-R1.pt, Lumo-R1.1.pt, Lumo-R2-thu-nghiem.pt.
    LUMO_MODELS_DIR: str = "app/ml/models"
    # Số lần chạy có lấy mẫu thêm cho mỗi mô hình (sau lượt greedy), chỉ khi còn thời gian.
    LUMO_SAMPLES: int = 2
    # Thời gian tối đa (giây) cho cả phần mô hình AI của một job (trong giới hạn thời gian của request).
    LUMO_TIME_BUDGET_SEC: float = 8.0

    # GA + SA của gói PRO / ULTIMATE: ngân sách thời gian (giây, trong giới hạn của request)
    # và số lần giải mã tối đa (mỗi lần giải mã là một lượt EP + DBLF đầy đủ ràng buộc).
    METAHEURISTIC_TIME_BUDGET_SEC: float = 6.0
    METAHEURISTIC_MAX_EVALUATIONS: int = 60
    # Ngân sách GA + SA được nới tới đủ chừng này lần giải mã khi chuyến lớn (giải mã chậm).
    METAHEURISTIC_MIN_DECODES: int = 5

    LOADMASTER_SERVICE_URL: str = "http://localhost:8080"
    REDIS_URL: str = "redis://localhost:6379"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    def cors_origins(self) -> list[str]:
        raw = self.CORS_ALLOWED_ORIGINS or self.FRONTEND_URL
        return [origin.strip() for origin in raw.split(",") if origin.strip() and origin.strip() != "*"]

    def api_docs_enabled(self) -> bool:
        if self.API_DOCS_ENABLED is not None:
            return self.API_DOCS_ENABLED
        return self.APP_ENV.strip().lower() in DEV_ENVIRONMENTS


DEV_ENVIRONMENTS = {"development", "dev", "local"}

settings = Settings()
