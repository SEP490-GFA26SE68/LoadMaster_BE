package fu.se184491.loadmaster_be.repository.vehicle;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Read projection: effective status is selected, filtered and ordered in the same SQL statement. */
@Repository
public class VehicleListRepository {
    @PersistenceContext
    private EntityManager em;
    public static final List<TripStatus> OCCUPYING_STATUSES = List.of(TripStatus.LOADING, TripStatus.IN_TRANSIT);
    private static final List<VehicleStatus> STATUS_ORDER = List.of(
        VehicleStatus.AVAILABLE, VehicleStatus.IN_TRIP, VehicleStatus.MAINTENANCE, VehicleStatus.RETIRED);
    private static final Set<String> SORT_FIELDS = Set.of(
        "id", "name", "licensePlate", "vehicleTypeId", "vehicleTypeName", "companyName", "status");

    /** Called by the service before repository exception translation can wrap validation failures. */
    public static void validateRequest(int page, int size, String sortBy, String sortDir) {
        PageRequest.of(page, size);
        if (sortBy != null && !SORT_FIELDS.contains(sortBy))
            throw new IllegalArgumentException("Unsupported vehicle sort field");
        if (sortDir != null && !Set.of("ASC","DESC").contains(sortDir.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("sortDir must be ASC or DESC");
    }

    public Page<VehicleResponse> search(Long companyId, int page, int size, String search,
            Long typeId, VehicleStatus status, String sortBy, String sortDir) {
        var window = PageRequest.of(page, size);
        String field = sortBy == null ? "name" : sortBy;
        if (!SORT_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported vehicle sort field");
        String direction = sortDir == null ? "ASC" : sortDir;
        if (!Set.of("ASC","DESC").contains(direction.toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("sortDir must be ASC or DESC");
        boolean desc = direction.equalsIgnoreCase("DESC");
        CriteriaBuilder cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(Vehicle.class);
        var type = root.join("vehicleType");
        var company = root.join("company");
        var rank = statusRank(root, query, cb);
        // Keep enum hydration validation for storedStatus while returning the effective rank.
        query.multiselect(root.get("id").alias("id"), root.get("name").alias("name"),
            root.get("licensePlate").alias("licensePlate"),root.get("status").alias("storedStatus"),
            type.get("id").alias("typeId"),
            type.get("name").alias("typeName"),company.get("id").alias("companyId"),
            company.get("companyName").alias("companyName"),rank.alias("rank"));
        query.where(filters(companyId, search, typeId, status, root, rank, cb));
        Expression<?> sort = switch(field) {
            case "vehicleTypeId" -> type.get("id");
            case "vehicleTypeName" -> type.get("name");
            case "companyName" -> company.get("companyName");
            case "status" -> rank;
            default -> root.get(field); // field has already passed the whitelist.
        };
        List<jakarta.persistence.criteria.Order> order = new ArrayList<>();
        order.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(sort),1).otherwise(0)));
        order.add(desc ? cb.desc(sort) : cb.asc(sort));
        if (!field.equals("id")) order.add(cb.asc(root.get("id")));
        query.orderBy(order);
        var rows = em.createQuery(query).setFirstResult(Math.toIntExact(window.getOffset()))
            .setMaxResults(size).getResultList().stream().map(this::response).toList();
        var count = cb.createQuery(Long.class);
        var countRoot = count.from(Vehicle.class);
        count.select(cb.count(countRoot));
        count.where(filters(companyId, search, typeId, status, countRoot, statusRank(countRoot,count,cb), cb));
        return new PageImpl<>(rows, window, em.createQuery(count).getSingleResult());
    }
    private Expression<Integer> statusRank(Root<Vehicle> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        var occupied = query.subquery(Integer.class);
        var trip = occupied.from(Trip.class);
        occupied.select(cb.literal(1)).where(cb.equal(trip.get("vehicle").get("id"),root.get("id")),
            trip.get("status").in(OCCUPYING_STATUSES));
        return cb.<Integer>selectCase()
            .when(cb.equal(root.get("status"),VehicleStatus.RETIRED),3)
            .when(cb.equal(root.get("status"),VehicleStatus.MAINTENANCE),2)
            .when(cb.exists(occupied),1).otherwise(0);
    }
    private Predicate[] filters(Long companyId, String search, Long typeId, VehicleStatus status,
            Root<Vehicle> root, Expression<Integer> rank, CriteriaBuilder cb) {
        List<Predicate> filters = new ArrayList<>();
        filters.add(cb.equal(root.get("company").get("id"),companyId));
        if (typeId != null) filters.add(cb.equal(root.get("vehicleType").get("id"),typeId));
        if (status != null) filters.add(cb.equal(rank,STATUS_ORDER.indexOf(status)));
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT)
                .replace("!","!!").replace("%","!%").replace("_","!_") + "%";
            filters.add(cb.or(cb.like(cb.lower(root.get("name")),pattern,'!'),
                cb.like(cb.lower(root.get("licensePlate")),pattern,'!'),
                cb.like(((org.hibernate.query.criteria.JpaExpression<?>)root.get("id")).cast(String.class),pattern,'!')));
        }
        return filters.toArray(Predicate[]::new);
    }
    private VehicleResponse response(Tuple row) {
        return new VehicleResponse(row.get("id",Long.class),row.get("name",String.class),
            row.get("licensePlate",String.class),row.get("typeId",Long.class),row.get("typeName",String.class),
            row.get("companyId",Long.class),row.get("companyName",String.class),
            STATUS_ORDER.get(row.get("rank",Integer.class)));
    }
}
