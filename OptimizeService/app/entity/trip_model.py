"""Read models for the trip/package part of the shared schema v3.4."""

from sqlalchemy import BigInteger, Boolean, Column, ForeignKey, Integer, Numeric, String
from sqlalchemy.orm import relationship
from sqlalchemy.types import TypeDecorator

from app.config.database import Base


class TripId(TypeDecorator):
    """BIGINT in PostgreSQL; accepts legacy UUID fixtures only in SQLite tests."""

    impl = String(64)
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            return dialect.type_descriptor(BigInteger())
        return dialect.type_descriptor(String(64))

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if dialect.name == "postgresql":
            return int(value)
        return str(value)

    def process_result_value(self, value, dialect):
        if value is None or dialect.name == "postgresql":
            return value
        return int(value) if value.isdigit() else value


def pk_id():
    return Column(
        Integer().with_variant(BigInteger, "postgresql"),
        primary_key=True,
        autoincrement=True,
    )


def bigint(nullable: bool = False, *, unique: bool = False):
    return Column(
        Integer().with_variant(BigInteger, "postgresql"),
        nullable=nullable,
        unique=unique,
    )


def fk_id(target: str, nullable: bool = False, *, unique: bool = False):
    return Column(
        Integer().with_variant(BigInteger, "postgresql"),
        ForeignKey(target),
        nullable=nullable,
        unique=unique,
    )


class VehicleType(Base):
    __tablename__ = "vehicle_types"

    id = pk_id()
    company_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    name = Column(String(150), nullable=False, default="LEGACY-VEHICLE-TYPE")
    inner_length = Column(Integer, nullable=False)  # mm
    inner_width = Column(Integer, nullable=False)  # mm
    inner_height = Column(Integer, nullable=False)  # mm
    max_payload_kg = Column(Numeric(10, 2), nullable=False)

    vehicles = relationship("Vehicle", back_populates="vehicle_type")


class Vehicle(Base):
    __tablename__ = "vehicles"

    id = pk_id()
    company_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    vehicle_type_id = fk_id("vehicle_types.id")
    driver_user_id = bigint(nullable=True, unique=True)
    license_plate = Column(String(50), nullable=False, unique=True)
    front_axle_limit_kg = Column(Numeric(10, 2), nullable=False, default=0)
    rear_axle_limit_kg = Column(Numeric(10, 2), nullable=False, default=0)

    # Non-persistent compatibility for pre-v3.4 test/build callers.
    _legacy_status = None

    @property
    def status(self):
        return self._legacy_status

    @status.setter
    def status(self, value):
        self._legacy_status = value

    vehicle_type = relationship("VehicleType", back_populates="vehicles")
    trips = relationship("Trip", back_populates="vehicle")


class Trip(Base):
    __tablename__ = "trips"

    id = Column(TripId(), primary_key=True)
    company_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    trip_code = Column(String(50), nullable=False, unique=True, default="LEGACY-TRIP")
    vehicle_id = fk_id("vehicles.id", nullable=True)
    created_by_dispatcher_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    status = Column(String(30), nullable=False, default="DRAFT")

    vehicle = relationship("Vehicle", back_populates="trips")
    delivery_stops = relationship("DeliveryStop", back_populates="trip")


class DeliveryStop(Base):
    __tablename__ = "delivery_stops"

    id = pk_id()
    trip_id = Column(TripId(), ForeignKey("trips.id"), nullable=False)
    stop_sequence = Column(Integer, nullable=False)
    location_address = Column(String(300), nullable=False, default="")

    _legacy_stop_name = None

    @property
    def stop_name(self):
        return self._legacy_stop_name

    @stop_name.setter
    def stop_name(self, value):
        self._legacy_stop_name = value

    trip = relationship("Trip", back_populates="delivery_stops")
    orders = relationship("Order", back_populates="delivery_stop")


class Order(Base):
    __tablename__ = "orders"

    id = pk_id()
    company_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    order_code = Column(String(50), nullable=False, unique=True)
    customer_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    delivery_stop_id = fk_id("delivery_stops.id", nullable=True)
    total_weight_kg = Column(Numeric(10, 2), nullable=False, default=0)

    delivery_stop = relationship("DeliveryStop", back_populates="orders")
    packages = relationship("Package", back_populates="order")


class PackageType(Base):
    __tablename__ = "package_types"

    id = pk_id()
    company_id = Column(Integer().with_variant(BigInteger, "postgresql"), nullable=False, default=1)
    name = Column(String(150), nullable=False, default="LEGACY-PACKAGE-TYPE")
    length = Column(Integer, nullable=False)  # mm
    width = Column(Integer, nullable=False)  # mm
    height = Column(Integer, nullable=False)  # mm
    weight_kg = Column(Numeric(10, 2), nullable=False, default=0)
    max_stacking_weight_kg = Column(Numeric(10, 2), nullable=False, default=0)
    is_fragile = Column(Boolean, nullable=False, default=False)

    _legacy_type_code = None

    @property
    def type_code(self):
        return self._legacy_type_code

    @type_code.setter
    def type_code(self, value):
        self._legacy_type_code = value

    packages = relationship("Package", back_populates="package_type")


class Package(Base):
    __tablename__ = "packages"

    id = pk_id()
    order_id = fk_id("orders.id")
    package_type_id = fk_id("package_types.id")
    tracking_barcode = Column(String(100), nullable=True, unique=True)
    actual_length = Column(Integer, nullable=False, default=0)  # mm
    actual_weight_kg = Column(Numeric(10, 2), nullable=False)
    is_pinned = Column(Boolean, nullable=False, default=False)

    order = relationship("Order", back_populates="packages")
    package_type = relationship("PackageType", back_populates="packages")


# Transitional import aliases for older Sprint 3 modules. They map to the same
# v3.4 entities/tables and can be removed after those modules are migrated.
TransportOrder = Order
CargoPackage = Package
