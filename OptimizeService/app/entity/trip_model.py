from sqlalchemy import Column, Integer, BigInteger, String, ForeignKey, Numeric, Boolean
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
    max_payload_kg = Column(Numeric(10, 2), nullable=True)
    door_width = Column(Integer, nullable=True)
    door_height = Column(Integer, nullable=True)

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

    id = Column(String(64), primary_key=True)  # supports UUID or string
    vehicle_id = fk_id("vehicles.id", nullable=True)
    trip_code = Column(String(50), nullable=True)
    status = Column(String(50), nullable=True)

    vehicle = relationship("Vehicle", back_populates="trips")
    delivery_stops = relationship("DeliveryStop", back_populates="trip")


class DeliveryStop(Base):
    __tablename__ = "delivery_stops"

    id = pk_id()
    trip_id = Column(String(64), ForeignKey("trips.id"), nullable=False)
    stop_sequence = Column(Integer, nullable=False)
    stop_name = Column(String(150), nullable=True)

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
    allow_rotate_x = Column(Boolean, default=True)
    allow_rotate_y = Column(Boolean, default=True)
    allow_rotate_z = Column(Boolean, default=True)

    packages = relationship("CargoPackage", back_populates="package_type")


class CargoPackage(Base):
    __tablename__ = "packages"

    id = pk_id()
    order_id = fk_id("orders.id", nullable=False)
    package_type_id = fk_id("package_types.id", nullable=True)
    tracking_barcode = Column(String(100), nullable=True)
    actual_weight_kg = Column(Numeric(10, 2), nullable=True)

    order = relationship("TransportOrder", back_populates="packages")
    package_type = relationship("PackageType", back_populates="packages")
