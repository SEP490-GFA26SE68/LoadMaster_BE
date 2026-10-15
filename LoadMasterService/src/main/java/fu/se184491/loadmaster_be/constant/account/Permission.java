package fu.se184491.loadmaster_be.constant.account;

public enum Permission {

    USERS_READ,
    USERS_CREATE,
    USERS_UPDATE,
    USERS_LOCK,
    USERS_DELETE,

    AUDIT_READ,

    /** Lập kế hoạch chuyến, tối ưu tuyến, giám sát GPS / ETA (TripPlanning + Monitoring). */
    TRIP_MANAGE
}