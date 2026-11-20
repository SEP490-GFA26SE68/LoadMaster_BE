package fu.se184491.loadmaster_be.repository.trip;

/** Một dòng đếm theo chuyến: mã chuyến và số bản ghi của chuyến đó. */
public interface TripCodeCount {

    String getTripCode();

    Long getTotal();
}
