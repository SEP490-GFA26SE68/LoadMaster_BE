from __future__ import annotations

from enum import Enum
from typing import Optional, Set, Dict


class SubscriptionTier(str, Enum):
    BASIC = "BASIC"
    PRO = "PRO"
    ULTIMATE = "ULTIMATE"


class AlgorithmName(str, Enum):
    EP_DBLF = "EP_DBLF"
    EP_DBLF_GA = "EP_DBLF_GA"
    EP_DBLF_GA_AI = "EP_DBLF_GA_AI"
    DEFAULT_GREEDY = "DEFAULT_GREEDY"


class AlgorithmTierService:
    """
    Service quản lý việc chọn thuật toán xếp hàng dựa trên subscription tier của Company (S5b-05).
    Quy tắc:
      - BASIC: EP + DBLF (greedy in-process)
      - PRO: EP + DBLF + GA (local search / di truyền)
      - ULTIMATE: EP + DBLF + GA + AI
    """

    TIER_DEFAULT_ALGORITHM: Dict[str, str] = {
        SubscriptionTier.BASIC.value: AlgorithmName.EP_DBLF.value,
        SubscriptionTier.PRO.value: AlgorithmName.EP_DBLF_GA.value,
        SubscriptionTier.ULTIMATE.value: AlgorithmName.EP_DBLF_GA_AI.value,
    }

    TIER_ALLOWED_ALGORITHMS: Dict[str, Set[str]] = {
        SubscriptionTier.BASIC.value: {
            AlgorithmName.EP_DBLF.value,
            AlgorithmName.DEFAULT_GREEDY.value,
            "BASIC",
        },
        SubscriptionTier.PRO.value: {
            AlgorithmName.EP_DBLF.value,
            AlgorithmName.DEFAULT_GREEDY.value,
            AlgorithmName.EP_DBLF_GA.value,
            "GA",
            "PRO",
            "BASIC",
        },
        SubscriptionTier.ULTIMATE.value: {
            AlgorithmName.EP_DBLF.value,
            AlgorithmName.DEFAULT_GREEDY.value,
            AlgorithmName.EP_DBLF_GA.value,
            AlgorithmName.EP_DBLF_GA_AI.value,
            "GA",
            "AI",
            "ULTIMATE",
            "PRO",
            "BASIC",
        },
    }

    @classmethod
    def normalize_tier(cls, tier: Optional[str]) -> str:
        """Chuẩn hóa subscription tier, mặc định BASIC nếu không hợp lệ."""
        if not tier:
            return SubscriptionTier.BASIC.value
        t_upper = str(tier).strip().upper()
        if t_upper in (SubscriptionTier.BASIC.value, SubscriptionTier.PRO.value, SubscriptionTier.ULTIMATE.value):
            return t_upper
        return SubscriptionTier.BASIC.value

    @classmethod
    def resolve_algorithm(
        cls,
        subscription_tier: Optional[str],
        requested_algorithm: Optional[str] = None,
    ) -> str:
        """
        Chọn thuật toán dựa trên subscription tier.
        Nếu client gửi requested_algorithm và tier cho phép thì dùng requested_algorithm,
        ngược lại fallback về default algorithm của tier.
        """
        tier = cls.normalize_tier(subscription_tier)
        if requested_algorithm:
            req_upper = requested_algorithm.strip().upper()
            if cls.is_algorithm_allowed(tier, req_upper):
                return req_upper

        return cls.TIER_DEFAULT_ALGORITHM.get(tier, AlgorithmName.EP_DBLF.value)

    @classmethod
    def is_algorithm_allowed(cls, subscription_tier: Optional[str], algorithm_name: str) -> bool:
        """Kiểm tra subscription tier có quyền sử dụng algorithm_name không."""
        tier = cls.normalize_tier(subscription_tier)
        allowed = cls.TIER_ALLOWED_ALGORITHMS.get(tier, set())
        return algorithm_name.strip().upper() in allowed

    @classmethod
    def get_algorithm_tier(cls, subscription_tier: Optional[str]) -> str:
        """Trả về tên tier chuẩn hóa."""
        return cls.normalize_tier(subscription_tier)
