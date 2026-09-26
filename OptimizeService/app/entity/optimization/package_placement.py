from sqlalchemy import Column, Integer, BigInteger, ForeignKey, Numeric, Boolean
from sqlalchemy.orm import relationship

from app.config.database import Base


class PackagePlacement(Base):
    __tablename__ = "package_placements"

    id = Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)
    load_plan_id = Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey("load_plans.id"), nullable=False)
    package_id = Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey("packages.id"), nullable=True)
    pos_x = Column(Numeric(10, 2), nullable=True)
    pos_y = Column(Numeric(10, 2), nullable=True)
    pos_z = Column(Numeric(10, 2), nullable=True)
    packed_length = Column(Numeric(10, 2), nullable=True)
    packed_width = Column(Numeric(10, 2), nullable=True)
    packed_height = Column(Numeric(10, 2), nullable=True)
    rotation_type = Column(Integer, nullable=True, default=0)
    step_sequence = Column(Integer, nullable=True, default=1)
    pinned = Column(Boolean, default=False)

    load_plan = relationship("LoadPlan", back_populates="placements")
    cargo_package = relationship("Package")

    @property
    def loading_sequence(self) -> int:
        return self.step_sequence or 1

    @loading_sequence.setter
    def loading_sequence(self, val: int) -> None:
        self.step_sequence = val
