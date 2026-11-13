"""
Một cấu hình DB cho cả hai dịch vụ: OptimizeService đọc được đúng các biến của LoadMasterService.

- DATABASE_URL dạng JDBC (jdbc:postgresql://host:5432/db?sslmode=require) hoặc dạng SQLAlchemy / libpq
  (postgresql://user:pass@host/db, postgres://..., postgresql+psycopg2://...) đều được.
- Tài khoản: ghi trong URL (user:pass@ hoặc ?user=&password= kiểu JDBC) thì dùng URL; không có thì lấy
  POSTGRES_USER / POSTGRES_PASSWORD như Spring Boot (spring.datasource.username / password).
- Tham số của URL: giữ tham số libpq (sslmode, sslrootcert, connect_timeout...); sslmode ghi rõ thì giữ nguyên;
  ssl=true không kèm sslmode thành sslmode=verify-full như pgjdbc (kiểm chứng chỉ và tên máy, sslrootcert nếu có
  đi cùng), ssl=false không thêm gì; currentSchema thành search_path; tham số chỉ JDBC hiểu (prepareThreshold...) bị bỏ — psycopg2
  từ chối tham số lạ.

Không ghi URL hay mật khẩu vào log: chỉ tên tham số bị bỏ.
"""
import logging
from typing import Optional

from sqlalchemy.engine import URL, make_url

logger = logging.getLogger(__name__)

# Tham số kết nối libpq mà psycopg2 nhận (https://www.postgresql.org/docs/current/libpq-connect.html)
LIBPQ_PARAMS = frozenset({
    "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "sslcrl", "sslcompression",
    "sslsni", "ssl_min_protocol_version", "ssl_max_protocol_version", "requiressl", "gssencmode",
    "channel_binding", "connect_timeout", "application_name", "fallback_application_name",
    "options", "target_session_attrs", "keepalives", "keepalives_idle", "keepalives_interval",
    "keepalives_count", "tcp_user_timeout", "client_encoding", "service", "krbsrvname",
})

_TRUE = {"true", "1", "yes", "on"}


def _single(value) -> str:
    return value[-1] if isinstance(value, tuple) else value


def sqlalchemy_database_url(
    database_url: str,
    user: Optional[str] = None,
    password: Optional[str] = None,
) -> URL:
    """URL cho create_engine từ DATABASE_URL (JDBC hoặc SQLAlchemy) và POSTGRES_USER / POSTGRES_PASSWORD."""
    raw = database_url.strip()
    if raw[:5].lower() == "jdbc:":
        raw = raw[5:]
    if raw.startswith("postgres://"):
        raw = "postgresql://" + raw[len("postgres://"):]

    url = make_url(raw)
    if url.get_backend_name() != "postgresql":
        return url
    if url.drivername == "postgresql":
        url = url.set(drivername="postgresql+psycopg2")

    query = {key: _single(value) for key, value in url.query.items()}
    query_user = query.pop("user", None)
    query_password = query.pop("password", None)

    ssl = query.pop("ssl", None)
    if ssl is not None and ssl.strip().lower() in _TRUE and "sslmode" not in query:
        # pgjdbc: ssl=true không kèm sslmode là verify-full — không hạ xuống require
        query["sslmode"] = "verify-full"
    schema = query.pop("currentSchema", None)
    if schema and "options" not in query:
        query["options"] = f"-c search_path={schema}"

    dropped = sorted(key for key in query if key not in LIBPQ_PARAMS)
    if dropped:
        logger.warning("DATABASE_URL: bỏ tham số chỉ JDBC dùng: %s", ", ".join(dropped))
    query = {key: value for key, value in query.items() if key in LIBPQ_PARAMS}

    return url.set(
        username=url.username or query_user or user or None,
        password=url.password or query_password or password or None,
        query=query,
    )
