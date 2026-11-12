"""CORS chỉ cho origin đã khai; tài liệu API chỉ bật ở môi trường dev hoặc khi bật bằng biến môi trường."""
from starlette.testclient import TestClient

from app.config.settings import Settings
from app.main import app


def make_settings(**values) -> Settings:
    base = {"DATABASE_URL": "sqlite://", "KEYCLOAK_CLIENT_SECRET": "x", "_env_file": None}
    return Settings(**{**base, **values})


class TestCorsOrigins:
    def test_defaults_to_local_web(self):
        assert make_settings(CORS_ALLOWED_ORIGINS=None).cors_origins() == ["http://localhost:5173"]

    def test_reads_comma_separated_list_and_drops_wildcard(self):
        settings = make_settings(CORS_ALLOWED_ORIGINS="https://a.example, *, https://b.example")
        assert settings.cors_origins() == ["https://a.example", "https://b.example"]

    def test_falls_back_to_frontend_url(self):
        settings = make_settings(CORS_ALLOWED_ORIGINS=None, FRONTEND_URL="https://web.example")
        assert settings.cors_origins() == ["https://web.example"]

    def test_unknown_origin_gets_no_cors_header(self):
        response = TestClient(app).options(
            "/health",
            headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"},
        )
        assert "access-control-allow-origin" not in response.headers

    def test_configured_origin_is_allowed_without_credentials(self):
        origin = app.user_middleware[0].kwargs["allow_origins"][0]
        response = TestClient(app).options(
            "/health",
            headers={"Origin": origin, "Access-Control-Request-Method": "GET"},
        )
        assert response.headers["access-control-allow-origin"] == origin
        assert "access-control-allow-credentials" not in response.headers


class TestApiDocs:
    def test_enabled_in_development(self):
        assert make_settings(APP_ENV="development").api_docs_enabled() is True

    def test_disabled_outside_development(self):
        assert make_settings(APP_ENV="production").api_docs_enabled() is False

    def test_explicit_flag_wins(self):
        assert make_settings(APP_ENV="production", API_DOCS_ENABLED=True).api_docs_enabled() is True
        assert make_settings(APP_ENV="development", API_DOCS_ENABLED=False).api_docs_enabled() is False
