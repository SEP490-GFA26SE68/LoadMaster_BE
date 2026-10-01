from typing import Optional, List, Any
from pydantic import BaseModel, Field, ConfigDict


class MetricDiff(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    plan1: Optional[float] = None
    plan2: Optional[float] = None
    diff: Optional[float] = None


class StopDeadlineFeasibility(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    stop_id: Optional[Any] = Field(None, alias="stopId")
    stop_name: Optional[str] = Field(None, alias="stopName")
    planned_arrival: Optional[str] = Field(None, alias="plannedArrival")
    deadline: Optional[str] = Field(None, alias="deadline")
    is_feasible: bool = Field(True, alias="isFeasible")
    delay_minutes: float = Field(0.0, alias="delayMinutes")


class DeadlineFeasibilityResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    is_feasible: bool = Field(True, alias="isFeasible")
    stops: List[StopDeadlineFeasibility] = []


class LoadPlanComparisonResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    plan1_id: int = Field(alias="plan1Id")
    plan2_id: int = Field(alias="plan2Id")
    plan1_name: Optional[str] = Field(None, alias="plan1Name")
    plan2_name: Optional[str] = Field(None, alias="plan2Name")

    volume_utilization: MetricDiff = Field(alias="volumeUtilization")
    weight_utilization: MetricDiff = Field(alias="weightUtilization")
    packed_items_count: MetricDiff = Field(alias="packedItemsCount")

    cog_x: MetricDiff = Field(alias="cogX")
    cog_y: MetricDiff = Field(alias="cogY")
    cog_z: MetricDiff = Field(alias="cogZ")
    front_axle_load: MetricDiff = Field(alias="frontAxleLoad")
    rear_axle_load: MetricDiff = Field(alias="rearAxleLoad")
    rehandling_count: MetricDiff = Field(alias="rehandlingCount")

    deadline_feasibility: DeadlineFeasibilityResponse = Field(alias="deadlineFeasibility")
