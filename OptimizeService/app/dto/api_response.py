from __future__ import annotations
from typing import Generic, TypeVar, Optional, Any
from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """
    Standard API Response envelope for OptimizeService.
    Aligned with LoadMasterService ApiResponse.
    """
    success: bool = True
    code: Optional[str] = None
    message: Optional[str] = None
    data: Optional[T] = None
    errors: Optional[Any] = None

    @classmethod
    def ok(
        cls,
        data: Optional[T] = None,
        message: Optional[str] = None,
    ) -> ApiResponse[T]:
        return cls(success=True, data=data, message=message)

    @classmethod
    def error(
        cls,
        code: str,
        message: str,
        errors: Optional[Any] = None,
    ) -> ApiResponse[None]:
        return cls(success=False, code=code, message=message, errors=errors)
