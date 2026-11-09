"""
Kiểm bộ kiện đã ghim trước khi chạy tối ưu.

Kiện ghim là hộp cố định của mọi cách xếp: không được dời để sửa lỗi, nên bộ ghim phải đứng
vững một mình — không ra ngoài thùng, không chồng nhau, không lơ lửng (chỗ đỡ chỉ tính kiện ghim
khác hoặc sàn, vì kiện còn lại sẽ được xếp lại), không sai hướng xoay, không đè kiện dễ vỡ hay
vượt tải chồng, tổng khối lượng không vượt tải trọng và không vượt tải trục.
Đơn vị: cm, kg.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest
from app.service.optimize.constraint_engine import ConstraintEngine


@dataclass(frozen=True)
class PinnedIssue:
    package_id: str
    code: str

    def as_dict(self) -> dict[str, str]:
        return {"package_id": self.package_id, "code": self.code}


def _oriented_size(pkg: PackageData, rotation: int) -> tuple[float, float, float]:
    # Cùng quy ước với bộ giải (OptimizationClient._oriented_size)
    from app.service.optimize.optimization_client import OptimizationClient
    return OptimizationClient._oriented_size(pkg, rotation)


def _placements(problem: ProblemRequest) -> tuple[list[PlacementData], list[PinnedIssue]]:
    packages = {str(p.id): p for p in problem.packages}
    issues: list[PinnedIssue] = []
    seen: set[str] = set()
    result: list[PlacementData] = []
    for pin in problem.pinned or []:
        key = str(pin.package_id)
        pkg = packages.get(key)
        if pkg is None:
            issues.append(PinnedIssue(key, "PINNED_PACKAGE_UNKNOWN"))
            continue
        if key in seen:
            issues.append(PinnedIssue(key, "PINNED_DUPLICATE"))
            continue
        seen.add(key)
        try:
            rotation = int(pin.rotation_type)
        except (TypeError, ValueError):
            rotation = 0
        size = _oriented_size(pkg, rotation)
        result.append(PlacementData(
            package_id=key,
            x=float(pin.pos_x),
            y=float(pin.pos_y),
            z=float(pin.pos_z),
            packed_l=float(pin.packed_l) if pin.packed_l is not None else size[0],
            packed_w=float(pin.packed_w) if pin.packed_w is not None else size[1],
            packed_h=float(pin.packed_h) if pin.packed_h is not None else size[2],
            rotation_type=rotation,
            step_sequence=len(result) + 1,
            pinned=True,
        ))
    return result, issues


def pinned_set_issues(problem: ProblemRequest) -> list[PinnedIssue]:
    """Mọi lý do bộ ghim không đứng vững; rỗng là hợp lệ (kể cả khi không ghim kiện nào)."""
    if not problem.pinned:
        return []
    packages = {str(p.id): p for p in problem.packages}
    placements, issues = _placements(problem)
    vehicle = problem.vehicle
    for index, placement in enumerate(placements):
        key = str(placement.package_id)
        others = placements[:index] + placements[index + 1:]
        below = placements[:index]
        checks = [
            ConstraintEngine.check_boundary(placement, vehicle),
            ConstraintEngine.check_non_overlap(placement, others),
            ConstraintEngine.check_support_area(placement, others),
            ConstraintEngine.check_rotation_allowed(packages[key], placement),
            ConstraintEngine.check_fragility(placement, others, packages),
            ConstraintEngine.check_stacking(placement, others, packages),
            ConstraintEngine.check_payload(placement, below, vehicle, packages),
        ]
        for result in checks:
            if not result.passed:
                issues.append(PinnedIssue(key, result.violation_code or "PINNED_INVALID"))
    if placements:
        axle = ConstraintEngine.check_axle_load(placements, vehicle, packages)
        # Xe chưa khai trục thì không kiểm tải trục (như lúc xếp)
        if not axle.passed and axle.violation_code == "AXLE_LOAD_EXCEEDED":
            issues.append(PinnedIssue("", axle.violation_code))
    return _unique(issues)


def _unique(issues: list[PinnedIssue]) -> list[PinnedIssue]:
    seen: set[tuple[str, str]] = set()
    result: list[PinnedIssue] = []
    for issue in issues:
        marker = (issue.package_id, issue.code)
        if marker not in seen:
            seen.add(marker)
            result.append(issue)
    return result
