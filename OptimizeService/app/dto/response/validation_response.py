from __future__ import annotations
from pydantic import BaseModel


class ValidationResponse(BaseModel):
    """
    Kết quả validate trip trước khi chạy optimization.

    can_optimize: True nếu không có errors (warnings OK)
    warnings: danh sách cảnh báo không chặn optimization
    errors: danh sách lỗi chặn optimization
    """
    can_optimize: bool
    warnings: list[str]
    errors: list[str]
