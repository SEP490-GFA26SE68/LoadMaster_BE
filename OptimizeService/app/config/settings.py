
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # Cùng biến và cùng mặc định với LoadMasterService: DATABASE_URL dạng JDBC (jdbc:postgresql://...) hoặc
    DATABASE_URL: str = "jdbc:postgresql://localhost:5432/loadmaster"
    POSTGRES_USER: Optional[str] = None
    POSTGRES_PASSWORD: Optional[str] = None

    OPTIMIZATION_LIFECYCLE_V2_ENABLED: bool = False
    OPTIMIZATION_LEASE_SECONDS: int = 30
    OPTIMIZATION_HEARTBEAT_SECONDS: int = 5
    OPTIMIZATION_EXECUTION_GRACE_SECONDS: int = 300

    # Luật ràng buộc vật lý mới (spec "Ràng buộc vật lý và điểm phương án" mục 5; KE-HOACH-TOI-UU M4):
    # H7 áp lực chồng kg/m² (thay HC-07 + HC-08), H8 ρ theo kiện, H10 tải sàn, H11 chỉ trọng tâm ngang,
    # H12 mã LIFO_BLOCKED, H13 kiện bắt buộc. Tắt = luật cũ, kết quả y hệt bản trước.
    PHYSICS_RULES_V2: bool = False
    # Chỉ có tác dụng khi PHYSICS_RULES_V2 bật và bài có kiện must_load: phương án một lượt thiếu kiện bắt buộc thì dựng
    # lại hai lượt — lượt 1 đặt kiện bắt buộc của mọi vùng (vùng sâu trước), lượt 2 xếp phần còn lại — giữ khi đủ kiện bắt
    # buộc và hợp lệ, rồi đánh lại step_sequence theo thứ tự bốc lên xe. Tắt = như M4.
    H13_TWO_PASS: bool = True

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

    # Một hạn chót cho cả job: mọi lượt EP, lần giải mã GA / SA và bước sửa cân bằng dùng chung hạn của request
    # (tắt: mỗi lượt EP nhận hạn mới = time_limit_sec, job có thể chạy gần gấp đôi giới hạn).
    OPTIMIZATION_SHARED_DEADLINE_ENABLED: bool = False
    # Bước sửa cân bằng (LoadBalanceSearch) có giới hạn: ngân sách = phần của time_limit_sec cho mỗi lần sửa,
    # dừng sau PATIENCE lần xếp lại liên tiếp không hơn, và mỗi lần kiểm dỡ hàng trong bước sửa tối đa
    # UNLOAD_CHECK_SEC giây (quá thì coi như chưa chứng minh được → không nhận, vẫn fail-closed).
    LOAD_BALANCE_BOUNDED_ENABLED: bool = False
    LOAD_BALANCE_BUDGET_RATIO: float = 0.25
    LOAD_BALANCE_PATIENCE: int = 4
    LOAD_BALANCE_UNLOAD_CHECK_SEC: float = 3.0
    # Phương án của bộ dựng được kiểm dỡ hàng trong tối đa INITIAL_CHECK_RATIO × thời gian còn lại của lượt
    # (không dưới UNLOAD_CHECK_SEC): đủ cho phương án lớn hợp lệ, mà phương án sai khó chứng minh không ăn hết lượt.
    LOAD_BALANCE_INITIAL_CHECK_RATIO: float = 0.5

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
