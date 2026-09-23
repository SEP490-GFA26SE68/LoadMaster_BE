from uuid import UUID
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.response.validation_response import ValidationResponse
from app.service.optimize.trip_validation_service import TripValidationService

router = APIRouter(prefix="/api/v1/validate", tags=["Validation"])


def get_trip_validation_service() -> TripValidationService:
    return TripValidationService()


@router.get("/trips/{trip_id}", response_model=ApiResponse[ValidationResponse])
async def validate_trip(
    trip_id: UUID,
    db: Session = Depends(get_db),
    validation_service: TripValidationService = Depends(get_trip_validation_service),
) -> ApiResponse[ValidationResponse]:
    """
    Validate a trip before running optimization.
    Returns 200 with ValidationResponse (can_optimize=True/False) inside ApiResponse.
    """
    result = validation_service.validate_trip_by_id(trip_id, db)
    return ApiResponse.ok(data=result, message="Kết quả kiểm tra chuyến đi")
