from enum import Enum


class OptimizationObjective(str, Enum):
    """Mục tiêu tối ưu của bài toán xếp hàng (cũng là mục tiêu của từng phương án ứng viên)."""
    MAX_VOLUME = "MAX_VOLUME"
    AXLE_BALANCE = "AXLE_BALANCE"
    MIN_REHANDLING = "MIN_REHANDLING"
