from sqlalchemy import Column, Integer, BigInteger, ForeignKey, Numeric, Boolean
from sqlalchemy.orm import relationship

from app.config.database import Base


class CenterOfGravity(Base):
    __tablename__ = "centers_of_gravity"

    id = Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)
    load_plan_id = Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey("load_plans.id"), unique=True, nullable=False)
    cog_x = Column(Numeric(8, 2), nullable=True)
    cog_y = Column(Numeric(8, 2), nullable=True)
    cog_z = Column(Numeric(8, 2), nullable=True)
    front_axle_load_kg = Column(Numeric(10, 2), nullable=True)
    rear_axle_load_kg = Column(Numeric(10, 2), nullable=True)
    is_balanced = Column(Boolean, default=True)

    load_plan = relationship("LoadPlan", backref="center_of_gravity")

    @property
    def is_axle_overload(self) -> bool:
        return not bool(self.is_balanced)

    @is_axle_overload.setter
    def is_axle_overload(self, value: bool) -> None:
        self.is_balanced = not value
