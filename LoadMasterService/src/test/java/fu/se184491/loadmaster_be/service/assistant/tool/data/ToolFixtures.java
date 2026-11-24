package fu.se184491.loadmaster_be.service.assistant.tool.data;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import jakarta.persistence.EntityManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;

/** Dữ liệu H2 dùng chung cho test công cụ của trợ lý: công ty, người dùng, xe, chuyến, phương án; đăng nhập bằng JWT. */
final class ToolFixtures {

    private final EntityManager entityManager;

    ToolFixtures(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    Company company(String code, String name) {
        Company company = Company.builder().companyCode(code).companyName(name).taxCode(code + "-TAX")
                .billingEmail(code.toLowerCase(Locale.ROOT) + "@loadmaster.test").status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        entityManager.persist(company);
        return company;
    }

    User user(Company company, String keycloakId, UserRole role, String fullName) {
        User user = User.builder().keycloakId(keycloakId).company(company).email(keycloakId + "@loadmaster.test")
                .fullName(fullName).userRoleType(role).status(UserStatus.ACTIVE).build();
        entityManager.persist(user);
        return user;
    }

    Vehicle vehicle(Company company, String plate) {
        VehicleType type = VehicleType.builder().company(company).name("Xe tải 3,5 tấn").innerLength(520)
                .innerWidth(200).innerHeight(200).maxPayloadKg(new BigDecimal("3500")).build();
        entityManager.persist(type);
        Vehicle vehicle = Vehicle.builder().company(company).vehicleType(type).name("Xe tải 3,5 tấn")
                .licensePlate(plate).build();
        entityManager.persist(vehicle);
        return vehicle;
    }

    Trip trip(Company company, Vehicle vehicle, User dispatcher, User driver, String code, String name,
              TripStatus status, LocalDateTime departure) {
        Trip trip = Trip.builder().company(company).vehicle(vehicle).createdByDispatcher(dispatcher).driver(driver)
                .tripCode(code).tripName(name).status(status).departureTime(departure).build();
        entityManager.persist(trip);
        return trip;
    }

    DeliveryStop stop(Trip trip, int sequence, String name) {
        DeliveryStop stop = DeliveryStop.builder().trip(trip).stopSequence(sequence).stopName(name)
                .address(name + ", Bình Dương").status(DeliveryStopStatus.PENDING).build();
        entityManager.persist(stop);
        return stop;
    }

    /** Một lần chạy một phương án, đã duyệt hay chưa, xếp được {@code packed} kiện. */
    LoadPlan plan(Trip trip, boolean approved, int packed) {
        OptimizationJob job = OptimizationJob.builder().trip(trip).jobUuid("job-" + trip.getTripCode()).build();
        entityManager.persist(job);
        LoadPlan plan = LoadPlan.builder().job(job).planName("Phương án C").candidateLabel("C")
                .objective("MIN_REHANDLING").approved(approved).stale(false).manuallyEdited(false)
                .packedItemsCount(packed).build();
        entityManager.persist(plan);
        return plan;
    }

    static void signIn(String keycloakId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(keycloakId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
