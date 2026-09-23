from enum import Enum


class OptimizationObjective(str, Enum):
    """Mục tiêu tối ưu của bài toán xếp hàng."""
    MAX_VOLUME = "MAX_VOLUME"
    AXLE_BALANCE = "AXLE_BALANCE"
