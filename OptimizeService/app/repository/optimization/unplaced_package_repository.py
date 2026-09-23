from typing import List
from sqlalchemy.orm import Session

from app.entity.optimization.unplaced_package import UnplacedPackage


class UnplacedPackageRepository:
    def create_all(self, unplaced: List[UnplacedPackage], db: Session) -> List[UnplacedPackage]:
        if unplaced:
            db.add_all(unplaced)
            db.flush()
        return unplaced

    def find_by_plan_id(self, plan_id: int, db: Session) -> List[UnplacedPackage]:
        return db.query(UnplacedPackage).filter(UnplacedPackage.load_plan_id == plan_id).all()
