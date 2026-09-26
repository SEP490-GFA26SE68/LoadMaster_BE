from typing import Optional
from sqlalchemy.orm import Session

from app.entity.optimization.center_of_gravity import CenterOfGravity


class CenterOfGravityRepository:
    def upsert(self, cog: CenterOfGravity, db: Session) -> CenterOfGravity:
        existing = db.query(CenterOfGravity).filter(CenterOfGravity.load_plan_id == cog.load_plan_id).first()
        if existing:
            existing.cog_x = cog.cog_x
            existing.cog_y = cog.cog_y
            existing.cog_z = cog.cog_z
            existing.front_axle_load_kg = cog.front_axle_load_kg
            existing.rear_axle_load_kg = cog.rear_axle_load_kg
            existing.is_balanced = cog.is_balanced
            db.flush()
            return existing
        else:
            db.add(cog)
            db.flush()
            return cog

    def find_by_load_plan_id(self, load_plan_id: int, db: Session) -> Optional[CenterOfGravity]:
        return db.query(CenterOfGravity).filter(CenterOfGravity.load_plan_id == load_plan_id).first()
