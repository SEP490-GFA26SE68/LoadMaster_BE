from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, Numeric, Boolean, DateTime
from sqlalchemy.orm import relationship

from app.config.database import Base


def pk_id():
    return Column(Integer().with_variant(BigInteger, "postgresql"), primary_key=True, autoincrement=True)


def fk_id(target: str, nullable: bool = True):
    return Column(Integer().with_variant(BigInteger, "postgresql"), ForeignKey(target), nullable=nullable)


class VehicleType(Base):
    __tablename__ = "vehicle_types"

    id = pk_id()
    name = Column(String(100), nullable=True)
    inner_length = Column(Integer, nullable=True)  # mm or m
    inner_width = Column(Integer, nullable=True)
    inner_height = Column(Integer, nullable=True)
    door_width = Column(Integer, nullable=True)
    door_height = Column(Integer, nullable=True)
    max_payload_kg = Column(Numeric(10, 2), nullable=True)

    # HC-11: Center of Gravity
    max_cog_offset_ratio = Column(
        Numeric(5, 4),
        nullable=False,
    )

    # HC-12: Axle Load
    front_axle_limit_kg = Column(
        Numeric(10, 2),
        nullable=True,
    )

    rear_axle_limit_kg = Column(
        Numeric(10, 2),
        nullable=True,
    )

    # Vị trí hai trục tính bằng cm.
    # X = 0 tại mặt trước bên trong khoang hàng.
    front_axle_x = Column(
        Numeric(10, 2),
        nullable=True,
    )

    rear_axle_x = Column(
        Numeric(10, 2),
        nullable=True,
    )

    # Tải trọng xe rỗng trên từng trục, đơn vị kg.
    empty_front_axle_load_kg = Column(
        Numeric(10, 2),
        nullable=True,
    )

    empty_rear_axle_load_kg = Column(
        Numeric(10, 2),
        nullable=True,
    )


    vehicles = relationship("Vehicle", back_populates="vehicle_type")


class Vehicle(Base):
    __tablename__ = "vehicles"

    id = pk_id()
    vehicle_type_id = fk_id("vehicle_types.id", nullable=True)
    license_plate = Column(String(30), nullable=False)
    status = Column(String(50), nullable=True)

    vehicle_type = relationship("VehicleType", back_populates="vehicles")
    trips = relationship("Trip", back_populates="vehicle")


class Trip(Base):
    __tablename__ = "trips"

    id = pk_id()  # supports UUID or string
    vehicle_id = fk_id("vehicles.id", nullable=True)
    trip_code = Column(String(50), nullable=True)
    status = Column(String(50), nullable=True)
    override_reason = Column(String(255), nullable=True)

    vehicle = relationship("Vehicle", back_populates="trips")
    delivery_stops = relationship("DeliveryStop", back_populates="trip")



class DeliveryRequirement(Base):
    __tablename__ = "delivery_requirements"

    id = pk_id()
    trip_id = fk_id("trips.id", nullable=True)

    # Mapping bảng trung gian DeliveryRequirement - Package
    # được thực hiện trong truy vấn SQLAlchemy.


class DeliveryRequirementPackage(Base):
    __tablename__ = "delivery_requirement_packages"

    delivery_requirement_id = Column(
        BigInteger,
        ForeignKey("delivery_requirements.id"),
        primary_key=True,
    )

    package_id = Column(
        BigInteger,
        ForeignKey("packages.id"),
        primary_key=True,
    )


class DeliveryStop(Base):
    __tablename__ = "delivery_stops"

    id = pk_id()
    trip_id = fk_id("trips.id", nullable=False)

    # DeliveryRequirement sinh ra DeliveryStop này.
    # Có thể NULL đối với điểm dừng được tạo thủ công.
    delivery_requirement_id = fk_id(
        "delivery_requirements.id",
        nullable=True,
    )

    stop_sequence = Column(Integer, nullable=False)
    stop_name = Column(String(150), nullable=True)
    latitude = Column(Numeric(10, 6), nullable=True)
    longitude = Column(Numeric(10, 6), nullable=True)
    planned_arrival = Column(DateTime, nullable=True)

    trip = relationship("Trip", back_populates="delivery_stops")
    orders = relationship("TransportOrder", back_populates="delivery_stop")


class TransportOrder(Base):
    __tablename__ = "orders"

    id = pk_id()
    delivery_stop_id = fk_id("delivery_stops.id", nullable=False)
    order_code = Column(String(50), nullable=True)

    delivery_stop = relationship("DeliveryStop", back_populates="orders")
    packages = relationship("CargoPackage", back_populates="order")


class PackageType(Base):
    __tablename__ = "package_types"

    id = pk_id()
    type_code = Column(String(50), nullable=True)
    name = Column(String(150), nullable=True)
    length = Column(Integer, nullable=True)
    width = Column(Integer, nullable=True)
    height = Column(Integer, nullable=True)
    max_stack_weight_kg = Column(Numeric(10, 2), nullable=True)

    # HC-03: Công tắc tổng cho phép xoay kiện hàng
    rotation_allowed = Column(Boolean, default=True)

    # HC-03: Quyền xoay theo từng trục
    allow_rotate_x = Column(Boolean, default=True)
    allow_rotate_y = Column(Boolean, default=True)
    allow_rotate_z = Column(Boolean, default=True)

    is_fragile = Column(Boolean, default=False)


    packages = relationship("CargoPackage", back_populates="package_type")


class CargoPackage(Base):
    __tablename__ = "packages"


    id = pk_id()
    order_id = fk_id("orders.id", nullable=False)
    package_type_id = fk_id("package_types.id", nullable=True)
    tracking_barcode = Column(String(100), nullable=True)
    actual_weight_kg = Column(Numeric(10, 2), nullable=True)

    # Kích thước thực tế do người dùng nhập cho từng Package (cm, tối đa 2 chữ số sau dấu phẩy).
    actual_length = Column(Numeric(10, 2), nullable=True)
    actual_width = Column(Numeric(10, 2), nullable=True)
    actual_height = Column(Numeric(10, 2), nullable=True)

    handling_class = Column(String(20), nullable=True, default="STANDARD")

    order = relationship("TransportOrder", back_populates="packages")
    package_type = relationship("PackageType", back_populates="packages")
