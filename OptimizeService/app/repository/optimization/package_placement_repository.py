from typing import List
from sqlalchemy.orm import Session

from app.entity.optimization.package_placement import PackagePlacement


class PackagePlacementRepository:
    def create_all(self, placements: List[PackagePlacement], db: Session) -> List[PackagePlacement]:
        if placements:
            db.add_all(placements)
            db.flush()
        return placements

    def find_by_plan_id(self, plan_id: int, db: Session) -> List[PackagePlacement]:
        return (
            db.query(PackagePlacement)
            .filter(PackagePlacement.load_plan_id == plan_id)
            .order_by(PackagePlacement.step_sequence.asc())
            .all()
        )
