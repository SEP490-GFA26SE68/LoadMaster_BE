"""
Ba phương án ứng viên của một job tối ưu xếp hàng.

Bộ giải nội bộ xếp thử vài cách (LayoutStrategy) trên cùng bài toán; mỗi mục tiêu
chọn cách tốt nhất theo chỉ số của chính nó:

  A  MAX_VOLUME      thể tích xếp được lớn nhất, hoà thì nhiều kiện hơn.
  B  AXLE_BALANCE    trong các cách xếp được nhiều kiện nhất: lệch mức dùng giữa
                     hai nhóm trục ít nhất.
  C  MIN_REHANDLING  trong các cách xếp được nhiều kiện nhất: ít kiện nằm ngoài
                     vùng điểm giao của nó nhất (rehandling_count).

Không mục tiêu nào đổi kiện lấy chỉ số; không sửa số cho khác đi: hai mục tiêu
chọn trùng một cách xếp thì hai phương án giống hệt nhau.
"""
from __future__ import annotations

from typing import Optional

from app.constant.optimization.objective import OptimizationObjective
from app.dto.optimization.engine.engine_response import (
    CandidatePlanData,
    EngineOptimizationResponse,
)


class LayoutStrategy:
    """Cách xếp của bộ giải. Chỉ đổi thứ tự xét kiện và cách chấm điểm chỗ đặt."""
    # Mặc định có từ trước: kiện to trước, chỗ đặt kéo trọng tâm về giữa thùng.
    CENTER_FIRST = "CENTER_FIRST"
    # DBLF: chỗ thấp nhất, sâu nhất, sát vách trái.
    DEEP_FIRST = "DEEP_FIRST"
    # Kiện nặng trước, chấm điểm như CENTER_FIRST.
    HEAVY_FIRST = "HEAVY_FIRST"
    # Kéo trọng tâm về điểm chia đều mức dùng của hai nhóm trục (cần đủ dữ liệu trục).
    AXLE_TARGET = "AXLE_TARGET"


CANDIDATE_OBJECTIVES: tuple[tuple[str, str], ...] = (
    ("A", OptimizationObjective.MAX_VOLUME.value),
    ("B", OptimizationObjective.AXLE_BALANCE.value),
    ("C", OptimizationObjective.MIN_REHANDLING.value),
)


def _axle_capacity(vehicle) -> Optional[tuple[float, float, float, float]]:
    """(giới hạn trước, giới hạn sau, tải rỗng trước, tải rỗng sau) khi xe khai đủ, không thì None."""
    values = (
        getattr(vehicle, "front_axle_limit_kg", None),
        getattr(vehicle, "rear_axle_limit_kg", None),
        getattr(vehicle, "empty_front_axle_load_kg", None),
        getattr(vehicle, "empty_rear_axle_load_kg", None),
    )
    if any(v is None for v in values):
        return None
    front_limit, rear_limit, empty_front, empty_rear = (float(v) for v in values)
    if front_limit <= 0 or rear_limit <= 0:
        return None
    return front_limit, rear_limit, empty_front, empty_rear


def axle_balance_target_x(vehicle, cargo_weight_kg: float) -> Optional[float]:
    """
    Vị trí X (cm) của trọng tâm hàng để hai nhóm trục cùng mức dùng (tải / giới hạn),
    theo mô hình đòn bẩy của HC-12. None khi xe thiếu dữ liệu trục hoặc không có hàng.
    """
    capacity = _axle_capacity(vehicle)
    front_x = getattr(vehicle, "front_axle_x", None)
    rear_x = getattr(vehicle, "rear_axle_x", None)
    if capacity is None or front_x is None or rear_x is None or cargo_weight_kg <= 0:
        return None
    wheelbase = float(rear_x) - float(front_x)
    if wheelbase <= 0:
        return None
    front_limit, rear_limit, empty_front, empty_rear = capacity
    weight = float(cargo_weight_kg)
    # rear_limit * (empty_front + W(1 - r)) = front_limit * (empty_rear + W r)
    rear_share = (rear_limit * (empty_front + weight) - front_limit * empty_rear) / (
        weight * (front_limit + rear_limit)
    )
    rear_share = min(max(rear_share, 0.0), 1.0)
    target = float(front_x) + rear_share * wheelbase
    return min(max(target, 0.0), float(vehicle.inner_l))


def layout_strategies_for(vehicle) -> list[str]:
    """Các cách xếp sẽ thử, theo thứ tự. AXLE_TARGET chỉ khi xe khai đủ dữ liệu trục."""
    strategies = [
        LayoutStrategy.CENTER_FIRST,
        LayoutStrategy.DEEP_FIRST,
        LayoutStrategy.HEAVY_FIRST,
    ]
    if (
        _axle_capacity(vehicle) is not None
        and getattr(vehicle, "front_axle_x", None) is not None
        and getattr(vehicle, "rear_axle_x", None) is not None
    ):
        strategies.append(LayoutStrategy.AXLE_TARGET)
    return strategies


def axle_imbalance(result: EngineOptimizationResponse, vehicle) -> Optional[float]:
    """
    Lệch mức dùng giữa hai nhóm trục: |tải trước / giới hạn trước − tải sau / giới hạn sau|.
    Xe chưa khai giới hạn trục: lệch tương đối |trước − sau| / (trước + sau).
    None khi phương án không có số tải trục (không có kiện nào).
    """
    metrics = result.metrics
    if metrics is None or metrics.front_axle_load is None or metrics.rear_axle_load is None:
        return None
    front = float(metrics.front_axle_load)
    rear = float(metrics.rear_axle_load)
    capacity = _axle_capacity(vehicle)
    if capacity is not None:
        front_limit, rear_limit, _, _ = capacity
        return abs(front / front_limit - rear / rear_limit)
    total = front + rear
    return abs(front - rear) / total if total > 0 else None


def _packed(result: EngineOptimizationResponse) -> int:
    return len(result.placements or [])


def _volume(result: EngineOptimizationResponse) -> float:
    return float(result.metrics.volume_utilization or 0.0) if result.metrics else 0.0


def _best(indexes: list[int], key) -> int:
    """Chỉ số có khoá nhỏ nhất; hoà thì cách xếp đứng trước (min ổn định)."""
    return min(indexes, key=lambda i: (key(i), i))


def pick_candidates(
    layouts: list[EngineOptimizationResponse],
    vehicle,
) -> list[CandidatePlanData]:
    """Chọn phương án A, B, C từ các cách xếp đã thử. Luôn trả đúng ba, theo thứ tự A, B, C."""
    if not layouts:
        raise ValueError("Cần ít nhất một cách xếp để chọn phương án")

    indexes = list(range(len(layouts)))
    best_volume = _best(indexes, lambda i: (-_volume(layouts[i]), -_packed(layouts[i])))

    most_packed = max(_packed(r) for r in layouts)
    full = [i for i in indexes if _packed(layouts[i]) == most_packed]

    def imbalance_key(i: int):
        value = axle_imbalance(layouts[i], vehicle)
        # Không có số tải trục thì xếp sau mọi cách có số.
        return (value is None, value or 0.0, -_volume(layouts[i]))

    best_axle = _best(full, imbalance_key)
    best_rehandling = _best(
        full,
        lambda i: (int(layouts[i].metrics.rehandling_count or 0), -_volume(layouts[i])),
    )

    chosen = {"A": best_volume, "B": best_axle, "C": best_rehandling}
    return [
        CandidatePlanData(label=label, objective=objective, result=layouts[chosen[label]])
        for label, objective in CANDIDATE_OBJECTIVES
    ]


def job_status_of(candidates: list[CandidatePlanData]) -> str:
    """NO_SOLUTION khi cả ba không xếp được kiện nào; COMPLETED khi có phương án xếp hết; còn lại PARTIAL."""
    from app.constant.optimization.job_status import OptimizationJobStatus

    if all(_packed(c.result) == 0 for c in candidates):
        return OptimizationJobStatus.NO_SOLUTION.value
    if any(_packed(c.result) > 0 and not c.result.unplaced for c in candidates):
        return OptimizationJobStatus.COMPLETED.value
    return OptimizationJobStatus.PARTIAL.value
