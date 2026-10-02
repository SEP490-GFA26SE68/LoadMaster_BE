from __future__ import annotations

from pydantic import BaseModel


class ConstraintResult(BaseModel):
    """Kết quả kiểm tra một ràng buộc xếp dỡ 3D."""
    passed: bool
    violation_code: str = ""
    detail: str = ""

    @classmethod
    def success(cls) -> ConstraintResult:
        return cls(passed=True, violation_code="", detail="")

    @classmethod
    def fail(cls, violation_code: str, detail: str) -> ConstraintResult:
        return cls(passed=False, violation_code=violation_code, detail=detail)
