package fu.se184491.loadmaster_be.constant.account;

public enum Permission {

    CUSTOMER_MANAGE,

    USERS_READ,
    USERS_CREATE,
    USERS_UPDATE,
    USERS_LOCK,
    USERS_DELETE,

    VEHICLE_TYPES_CREATE,
    VEHICLE_TYPES_READ,
    VEHICLE_TYPES_UPDATE,
    VEHICLE_TYPES_DELETE,

    VEHICLES_READ,
    VEHICLES_CREATE,
    VEHICLES_UPDATE,
    VEHICLES_DELETE,

    PACKAGE_TYPE_MANAGE,
    PACKAGE_MANAGE,


    DELIVERY_DEMAND_MANAGE,
    DELIVERY_READ,

    AUDIT_READ,

    /** Lập kế hoạch chuyến, tối ưu tuyến, giám sát GPS / ETA (TripPlanning + Monitoring). */
    TRIP_MANAGE
}