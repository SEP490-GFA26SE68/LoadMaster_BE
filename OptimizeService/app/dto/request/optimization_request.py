
from typing import List, Optional

from pydantic import BaseModel, Field

from app.constant.optimization.objective import OptimizationObjective


class JobPinnedPlacement(BaseModel):
    """Kiện ghim giữ nguyên vị trí và hướng trong mọi phương án của lần chạy (cm)."""
    package_id: int
    pos_x: float
    pos_y: float
    pos_z: float
    rotation_type: int = 0
    packed_length: Optional[float] = None
    packed_width: Optional[float] = None
    packed_height: Optional[float] = None


class OptimizationJobRequest(BaseModel):
    # Khớp với trips.id BIGINT trong PostgreSQL
    trip_id: int = Field(gt=0)

    objective: OptimizationObjective = OptimizationObjective.MAX_VOLUME

    time_limit_sec: int = Field(default=60, ge=10, le=600)

    seed: Optional[int] = None

    algorithm_name: Optional[str] = None

    company_id: Optional[str] = None

    subscription_tier: Optional[str] = "BASIC"

    # Giữ kiện đã ghim của một phương án: vắng hoặc rỗng là lần chạy bình thường
    pinned_placements: List[JobPinnedPlacement] = Field(default_factory=list)
