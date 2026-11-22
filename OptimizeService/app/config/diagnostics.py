"""Allowlisted diagnostic metadata; never format exception messages, locals or SQL values."""
import json
import logging
import os
import re
import sys
import time
import traceback
from contextvars import ContextVar
from pathlib import Path
from uuid import UUID, uuid4

logger = logging.getLogger("uvicorn.error")
_context = ContextVar("optimization_diagnostics", default=None)

def canonical_uuid(value):
    if not isinstance(value, str) or len(value) != 36:
        return None
    try:
        return str(UUID(value))
    except ValueError:
        return None

def context(request=None):
    if request is not None:
        return request.scope.get("state", {}).get("optimization_diagnostics")
    return _context.get()

def category(exc):
    from sqlalchemy.exc import TimeoutError, OperationalError, DisconnectionError, DBAPIError, SQLAlchemyError
    if isinstance(exc, TimeoutError): return "pool_timeout"
    if isinstance(exc, DisconnectionError): return "connection_disconnected"
    if isinstance(exc, OperationalError): return "database_operational"
    if isinstance(exc, DBAPIError): return "database_driver"
    if isinstance(exc, SQLAlchemyError): return "sqlalchemy_error"
    return "unclassified"

def safe_name(value):
    return value if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.<>-]{1,100}", value) else "redacted"

def emit(event, ctx=None, *, exc=None):
    ctx = ctx if ctx is not None else context()
    if ctx is None: return
    record = {"event": safe_name(event), "request_id": ctx["request_id"],
              "job_uuid": ctx.get("job_uuid"), "trip_id": ctx.get("trip_id"),
              "stage": ctx["stage"], "phase": "post_ack" if ctx.get("ack_started") else "pre_ack",
              "http_status": ctx.get("http_status"),
              "elapsed_ms": round((time.monotonic() - ctx["started"]) * 1000, 3)}
    if exc is not None:
        record["exception_type"] = safe_name(type(exc).__name__)
        record["reason_category"] = category(exc)
        # Frame-only traceback: no exception str/repr, source text, cause messages or locals.
        record["traceback"] = [{"file": safe_name(Path(f.filename).name), "line": f.lineno,
                                "function": safe_name(f.name)}
                               for f in traceback.extract_tb(exc.__traceback__, limit=24)]
    logger.log(logging.ERROR if exc else logging.INFO, json.dumps(record, ensure_ascii=True))

def mark(stage, *, job_uuid=None, trip_id=None):
    ctx = context()
    if ctx is None: return
    ctx["stage"] = safe_name(stage)
    if canonical_uuid(job_uuid): ctx["job_uuid"] = canonical_uuid(job_uuid)
    if isinstance(trip_id, int) and not isinstance(trip_id, bool) and trip_id > 0: ctx["trip_id"] = trip_id
    emit(stage, ctx)

def failure(exc, request=None, event=None):
    ctx = context(request) if request is not None else context()
    if ctx is None and request is not None:
        ctx = {"request_id": str(uuid4()), "stage": "untracked_request", "started": time.monotonic()}
    if ctx is None: return
    if ctx.get("logged_exception") is exc: return
    ctx["logged_exception"] = exc
    emit(event or ("post_ack_exception" if ctx.get("ack_started") else "pre_ack_exception"), ctx, exc=exc)

def startup_identity(module_file):
    revision = os.getenv("LOADMASTER_BUILD_REVISION", "unknown")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", revision): revision = "unknown"
    logger.info(json.dumps({"event": "worker_startup", "pid": os.getpid(),
                           "python_executable": sys.executable, "cwd": str(Path.cwd()),
                           "app_module": str(Path(module_file).resolve()), "revision": revision}, ensure_ascii=True))

class DiagnosticMiddleware:
    """Pure ASGI boundary: retain context through dependencies, response and background work.

    Consume logged unexpected exceptions after producing the existing generic 500 envelope,
    so Uvicorn cannot subsequently print an unsafe SQLAlchemy exception with SQL parameters.
    """
    def __init__(self, app): self.app = app
    async def __call__(self, scope, receive, send):
        target = scope.get("type") == "http" and (scope.get("path") == "/health" or
                 (scope.get("method") == "POST" and scope.get("path") in {"/api/v1/optimization/jobs", "/api/v2/optimization/jobs"}))
        if not target:
            return await self.app(scope, receive, send)
        raw_id = next((v.decode("ascii", errors="ignore") for k, v in scope.get("headers", []) if k.lower() == b"x-request-id"), "")
        ctx = {"request_id": canonical_uuid(raw_id) or str(uuid4()), "stage": "request_received",
               "started": time.monotonic(), "ack_started": False, "response_started": False}
        scope.setdefault("state", {})["optimization_diagnostics"] = ctx
        token = _context.set(ctx)
        async def observed_send(message):
            if message["type"] == "http.response.start":
                ctx["http_status"] = message["status"]
                ctx["response_started"] = True
                ctx["ack_started"] = message["status"] == 202
                emit("ack_202_started" if ctx["ack_started"] else "response_started", ctx)
            await send(message)
        try:
            emit("http_request_received", ctx)
            await self.app(scope, receive, observed_send)
        except Exception as exc:
            failure(exc)
            if not ctx["response_started"]:
                from starlette.requests import Request
                handler = scope["app"].exception_handlers[Exception]
                response = await handler(Request(scope), exc)
                await response(scope, receive, observed_send)
            # A response already sent cannot be replaced; keep the background failure in safe logs.
        finally:
            ctx.pop("logged_exception", None)
            _context.reset(token)
