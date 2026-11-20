"""CORS chỉ cho origin đã khai; tài liệu API chỉ bật ở môi trường dev hoặc khi bật bằng biến môi trường."""
from fastapi.middleware.cors import CORSMiddleware
from starlette.testclient import TestClient

from app.config.settings import Settings, settings
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
        assert response.status_code == 400
        assert "access-control-allow-origin" not in response.headers
        assert "access-control-allow-credentials" not in response.headers

    def test_configured_origin_is_allowed_without_credentials(self):
        cors = [middleware for middleware in app.user_middleware if middleware.cls is CORSMiddleware]
        assert len(cors) == 1
        options = cors[0].kwargs
        assert options["allow_origins"] == settings.cors_origins()
        assert options["allow_origins"]
        assert "*" not in options["allow_origins"]
        assert options["allow_credentials"] is False
        assert options["allow_methods"] == ["GET", "POST", "OPTIONS"]
        assert options["allow_headers"] == ["Authorization", "Content-Type"]
        with TestClient(app) as client:
            for origin in options["allow_origins"]:
                response = client.options(
                    "/health",
                    headers={
                        "Origin": origin,
                        "Access-Control-Request-Method": "GET",
                        "Access-Control-Request-Headers": "Authorization, Content-Type",
                    },
                )
                assert response.status_code == 200
                assert response.headers["access-control-allow-origin"] == origin
                assert "access-control-allow-credentials" not in response.headers
                assert set(response.headers["access-control-allow-methods"].split(", ")) == {
                    "GET", "POST", "OPTIONS"
                }
                allowed_headers = response.headers["access-control-allow-headers"].lower()
                assert "authorization" in allowed_headers
                assert "content-type" in allowed_headers


class TestApiDocs:
    def test_enabled_in_development(self):
        assert make_settings(APP_ENV="development").api_docs_enabled() is True

    def test_disabled_outside_development(self):
        assert make_settings(APP_ENV="production").api_docs_enabled() is False

    def test_explicit_flag_wins(self):
        assert make_settings(APP_ENV="production", API_DOCS_ENABLED=True).api_docs_enabled() is True
        assert make_settings(APP_ENV="development", API_DOCS_ENABLED=False).api_docs_enabled() is False
