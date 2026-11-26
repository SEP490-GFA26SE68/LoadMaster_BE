from __future__ import annotations

from typing import Optional, List, Union
from uuid import UUID
from pydantic import BaseModel, Field
from app.dto.optimization.engine.rotation_contract import RotationCode


class PlacementData(BaseModel):
    """Vị trí và hướng xoay của một kiện hàng được xếp thành công."""
    package_id: Union[UUID, str]
    x: float
    y: float
    z: float
    packed_l: float
    packed_w: float
    packed_h: float
    rotation_type: RotationCode = 0
    step_sequence: int = 1
    stop_zone_id: Optional[Union[UUID, str, int]] = None
    # Kiện đã ghim: giữ nguyên vị trí và hướng của phương án trước.
    pinned: bool = False
    # Lý do đặt + số đo của chỗ đặt (spec 7.3; chỉ điền khi PLAN_SCORE_J bật). Suy từ phương án cuối, không phải
    # nhật ký của bộ giải: mã vùng, nằm sàn / chịu tải, kiện bắt buộc, kiện ghim.
    reason_codes: Optional[List[str]] = None
    stack_pressure_kg_m2: Optional[float] = None       # pⱼ: áp lực trên mặt trên (H7); 0 khi không có gì đè
    top_load_capacity_kg_m2: Optional[float] = None    # σⱼ dùng khi kiểm H7 (trống = không nhận tải)
    support_ratio: Optional[float] = None              # diện tích đáy được đỡ / diện tích đáy (H8); 1 trên sàn
    distance_to_door_cm: Optional[float] = None        # L − (x + l)


class UnplacedData(BaseModel):
    """Kiện hàng không thể xếp kèm lý do."""
    package_id: Union[UUID, str]
    reason: str
    violated_constraints: List[str] = Field(default_factory=list)


class MetricsData(BaseModel):
    """Các chỉ số hiệu quả xếp hàng và thời gian tính toán."""
    volume_utilization: float = 0.0
    weight_utilization: float = 0.0
    packed_count: int = 0
    computation_ms: int = 0
    cog_x: Optional[float] = None
    cog_y: Optional[float] = None
    cog_z: Optional[float] = None
    front_axle_load: Optional[float] = None
    rear_axle_load: Optional[float] = None
    rehandling_count: int = 0


class PlanScoreComponentData(BaseModel):
    """Một dòng của bảng phân rã J: Vₖ, wₖ và đóng góp −100·wₖ·Vₖ."""
    code: str
    name: str
    value: float
    weight: float
    contribution: float
    computed: bool = True  # False: "không tính" (S3 khi xe thiếu dữ liệu trục; S7, S8 chưa làm)


class PlanScoreData(BaseModel):
    """Điểm J của phương án theo một hồ sơ ưu tiên (spec mục 6), kèm phân rã."""
    profile: str
    profile_name: str
    assumed_weights: bool = True  # trọng số còn là số giả định (Q-D4)
    j: float
    u_vol: float
    u_n: float
    w_vol: float
    w_n: float
    components: List[PlanScoreComponentData] = Field(default_factory=list)


class EngineOptimizationResponse(BaseModel):
    """Kết quả trả về từ Optimization Engine."""
    placements: List[PlacementData] = Field(default_factory=list)
    unplaced: List[UnplacedData] = Field(default_factory=list)
    metrics: MetricsData = Field(default_factory=MetricsData)
    # Ba phương án ứng viên của một job (A · B · C), chỉ bộ giải nội bộ điền.
    # Không đưa ra JSON: lần chạy lại và engine ngoài vẫn là một kết quả.
    candidates: List["CandidatePlanData"] = Field(default_factory=list, exclude=True)
    # Thuật toán đã sinh ra cách xếp này (mã của PlanAlgorithm: EP_DBLF, GA, SA, EP_LUMO_R1_1,
    # LUMO_R1, LUMO_R1_1, LUMO_R2). Engine ngoài không điền thì để trống.
    algorithm_name: Optional[str] = None
    # Các thuật toán job đã chạy (kể cả cách không được chọn); chỉ để ghi log, không đưa ra JSON.
    algorithms_tried: List[str] = Field(default_factory=list, exclude=True)
    # Điểm J + phân rã theo hồ sơ của phương án (M6, cờ PLAN_SCORE_J); tắt cờ thì để trống.
    plan_score: Optional[PlanScoreData] = None


class CandidatePlanData(BaseModel):
    """Một phương án ứng viên: nhãn (A | B | C), mục tiêu và kết quả xếp."""
    label: str
    objective: str
    result: EngineOptimizationResponse
    # Cờ PLAN_SCORE_J: hồ sơ đã chọn phương án này (A | B | C) và nhãn trước đó đã chọn trùng phương án (vd "A").
    profile: Optional[str] = None
    duplicate_of: Optional[str] = None


EngineOptimizationResponse.model_rebuild()


# Alias cho phép sử dụng cả 2 tên gọi OptimizationResult và EngineOptimizationResponse
OptimizationResult = EngineOptimizationResponse
