"""
OptimizeService đọc cùng cấu hình DB với LoadMasterService: DATABASE_URL dạng JDBC + POSTGRES_USER /
POSTGRES_PASSWORD, và vẫn nhận DATABASE_URL dạng SQLAlchemy như trước.
"""
import logging

import pytest

from app.config.database_url import sqlalchemy_database_url
from app.config.settings import Settings


def test_jdbc_url_with_java_credentials():
    url = sqlalchemy_database_url("jdbc:postgresql://db.example.vn:5432/loadmaster", "loadmaster", "s3cret")

    assert url.drivername == "postgresql+psycopg2"
    assert (url.host, url.port, url.database) == ("db.example.vn", 5432, "loadmaster")
    assert (url.username, url.password) == ("loadmaster", "s3cret")


def test_jdbc_url_keeps_sslmode():
    url = sqlalchemy_database_url(
        "jdbc:postgresql://pooler.example.com:6543/postgres?sslmode=require", "postgres.abc", "pw")

    assert dict(url.query) == {"sslmode": "require"}
    assert url.render_as_string(hide_password=False) == (
        "postgresql+psycopg2://postgres.abc:pw@pooler.example.com:6543/postgres?sslmode=require")


def test_password_with_special_characters_is_escaped_not_split():
    url = sqlalchemy_database_url("jdbc:postgresql://localhost:5432/loadmaster", "lm", "p@ss:w/rd#1")

    assert url.password == "p@ss:w/rd#1"
    assert url.host == "localhost"


def test_credentials_in_jdbc_query_are_moved_into_the_url():
    url = sqlalchemy_database_url(
        "jdbc:postgresql://localhost:5432/loadmaster?user=jdbcuser&password=jdbcpw&sslmode=verify-full",
        "envuser", "envpw")

    assert (url.username, url.password) == ("jdbcuser", "jdbcpw")
    assert dict(url.query) == {"sslmode": "verify-full"}


def test_jdbc_only_parameters_are_translated_or_dropped(caplog):
    with caplog.at_level(logging.WARNING):
        url = sqlalchemy_database_url(
            "jdbc:postgresql://localhost:5432/loadmaster?ssl=true&currentSchema=app&prepareThreshold=0",
            "u", "secret-pw")

    assert dict(url.query) == {"sslmode": "require", "options": "-c search_path=app"}
    assert "prepareThreshold" in caplog.text
    # Không lộ mật khẩu trong log
    assert "secret-pw" not in caplog.text


def test_old_sqlalchemy_style_url_still_works_and_wins_over_env_credentials():
    url = sqlalchemy_database_url(
        "postgresql://olduser:oldpw@localhost:5432/loadmaster?sslmode=disable", "envuser", "envpw")

    assert url.drivername == "postgresql+psycopg2"
    assert (url.username, url.password) == ("olduser", "oldpw")
    assert dict(url.query) == {"sslmode": "disable"}


@pytest.mark.parametrize("raw", [
    "postgres://u:p@localhost/loadmaster",
    "postgresql+psycopg2://u:p@localhost/loadmaster",
])
def test_other_postgres_spellings(raw):
    url = sqlalchemy_database_url(raw)

    assert url.drivername == "postgresql+psycopg2"
    assert (url.username, url.password, url.database) == ("u", "p", "loadmaster")


def test_url_without_credentials_and_no_env_has_none():
    url = sqlalchemy_database_url("jdbc:postgresql://localhost:5432/loadmaster", None, "")

    assert url.username is None and url.password is None


def test_sqlite_is_left_untouched():
    url = sqlalchemy_database_url("sqlite:///./pytest-ci.db", "u", "p")

    assert url.render_as_string(hide_password=False) == "sqlite:///./pytest-ci.db"


def test_settings_read_the_java_variables(monkeypatch):
    monkeypatch.setenv("DATABASE_URL", "jdbc:postgresql://db:5432/loadmaster?sslmode=require")
    monkeypatch.setenv("POSTGRES_USER", "loadmaster")
    monkeypatch.setenv("POSTGRES_PASSWORD", "pw")
    monkeypatch.setenv("KEYCLOAK_CLIENT_SECRET", "x")

    s = Settings(_env_file=None)
    url = sqlalchemy_database_url(s.DATABASE_URL, s.POSTGRES_USER, s.POSTGRES_PASSWORD)

    assert url.render_as_string(hide_password=False) == (
        "postgresql+psycopg2://loadmaster:pw@db:5432/loadmaster?sslmode=require")


def test_settings_default_matches_loadmaster_service(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("KEYCLOAK_CLIENT_SECRET", "x")

    assert Settings(_env_file=None).DATABASE_URL == "jdbc:postgresql://localhost:5432/loadmaster"
