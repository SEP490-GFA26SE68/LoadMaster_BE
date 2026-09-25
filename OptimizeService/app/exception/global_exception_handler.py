from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.dto.api_response import ApiResponse
from app.exception.app_exception import AppException, ServiceAuthenticationException
from app.exception.error_code import ErrorCode


def register_exception_handlers(app: FastAPI):
    @app.exception_handler(AppException)
    async def app_exception_handler(request: Request, exc: AppException):
        return JSONResponse(
            status_code=exc.http_status,
            content=ApiResponse.error(
                code=exc.error_code.code,
                message=exc.message,
            ).model_dump(),
        )

    @app.exception_handler(ServiceAuthenticationException)
    async def service_auth_exception_handler(request: Request, exc: ServiceAuthenticationException):
        return JSONResponse(
            status_code=exc.http_status,
            content=ApiResponse.error(
                code="SERVICE_AUTHENTICATION_ERROR",
                message=exc.message,
            ).model_dump(),
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException):
        return JSONResponse(
            status_code=exc.status_code,
            content=ApiResponse.error(
                code=f"HTTP_{exc.status_code}",
                message=str(exc.detail),
            ).model_dump(),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError):
        return JSONResponse(
            status_code=422,
            content=ApiResponse.error(
                code="VALIDATION_ERROR",
                message="Dữ liệu đầu vào không hợp lệ",
                errors=exc.errors(),
            ).model_dump(),
        )

    import httpx

    @app.exception_handler(httpx.TimeoutException)
    async def httpx_timeout_handler(request: Request, exc: httpx.TimeoutException):
        return JSONResponse(
            status_code=504,
            content=ApiResponse.error(
                code=ErrorCode.ENGINE_TIMEOUT.code,
                message=ErrorCode.ENGINE_TIMEOUT.message,
            ).model_dump(),
        )

    @app.exception_handler(httpx.HTTPStatusError)
    async def httpx_status_handler(request: Request, exc: httpx.HTTPStatusError):
        status_code = exc.response.status_code if exc.response is not None else 500
        if status_code == 401:
            code = ErrorCode.AUTH_ERROR.code
            msg = ErrorCode.AUTH_ERROR.message
        elif status_code == 403:
            code = ErrorCode.AUTH_FORBIDDEN.code
            msg = ErrorCode.AUTH_FORBIDDEN.message
        else:
            code = ErrorCode.OPTIMIZATION_FAILED.code
            msg = ErrorCode.OPTIMIZATION_FAILED.message
        return JSONResponse(
            status_code=status_code if status_code in (401, 403) else 502,
            content=ApiResponse.error(
                code=code,
                message=msg,
            ).model_dump(),
        )

    @app.exception_handler(httpx.RequestError)
    async def httpx_request_handler(request: Request, exc: httpx.RequestError):
        return JSONResponse(
            status_code=503,
            content=ApiResponse.error(
                code=ErrorCode.SERVICE_UNAVAILABLE.code,
                message=ErrorCode.SERVICE_UNAVAILABLE.message,
            ).model_dump(),
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception):
        return JSONResponse(
            status_code=500,
            content=ApiResponse.error(
                code=ErrorCode.INTERNAL_SERVER_ERROR.code,
                message=ErrorCode.INTERNAL_SERVER_ERROR.message,
            ).model_dump(),
        )
