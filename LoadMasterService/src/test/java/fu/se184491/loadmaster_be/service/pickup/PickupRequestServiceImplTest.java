package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopType;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.pickup.ApprovePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.CreatePickupRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.PickupPackageRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.PickupPointRequest;
import fu.se184491.loadmaster_be.dto.request.pickup.RejectPickupRequest;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequest;
import fu.se184491.loadmaster_be.entity.pickup.PickupRequestPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.pickup.PickupRoutePath.Point;
import fu.se184491.loadmaster_be.service.pickup.impl.PickupRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupRequestServiceImplTest {

    @Mock private PickupRequestRepository pickupRequestRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private TripLookupHelper tripLookupHelper;
    @Mock private PickupContextBuilder contextBuilder;

    @InjectMocks
    private PickupRequestServiceImpl service;

    private User dispatcher;
    private User driver;
    private Trip trip;
    private DeliveryStop current;
    private DeliveryStop next;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        dispatcher = User.builder().id(5L).company(company).build();
        driver = User.builder().id(8L).company(company).build();
        trip = Trip.builder().id(10L).company(company).driver(driver).status(TripStatus.IN_TRANSIT).build();
        current = DeliveryStop.builder().id(30L).trip(trip).stopSequence(1).status(DeliveryStopStatus.ARRIVED).build();
        next = DeliveryStop.builder().id(31L).trip(trip).stopSequence(2).status(DeliveryStopStatus.PENDING)
                .plannedArrival(LocalDateTime.of(2026, 11, 6, 15, 0)).build();
        lenient().when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        lenient().when(tripLookupHelper.requireTrip("10", 1L)).thenReturn(trip);
        lenient().when(pickupRequestRepository.save(any(PickupRequest.class))).thenAnswer(call -> call.getArgument(0));
        lenient().when(deliveryStopRepository.save(any(DeliveryStop.class))).thenAnswer(call -> {
            DeliveryStop stop = call.getArgument(0);
            if (stop.getId() == null) {
                stop.setId(100L + stop.getStopSequence());
            }
            return stop;
        });
        lenient().when(cargoPackageRepository.save(any(CargoPackage.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void signIn(User user, String role) {
        lenient().when(currentUserService.getCurrentUser()).thenReturn(user);
        lenient().when(currentUserService.hasRole(anyString())).thenAnswer(call -> role.equals(call.getArgument(0)));
    }

    private static FreedSpacePacker.Result packing(List<FreedSpacePacker.Unplaced> unplaced) {
        FreedSpacePacker.Snapshot empty = new FreedSpacePacker.Snapshot(0, null, null);
        return new FreedSpacePacker.Result(List.of(new FreedSpacePacker.Placement(0, 0, 500, 0, 0, 50, 50, 50)), unplaced, empty,
                empty, 0, 1_000_000);
    }

    /** Ngữ cảnh đạt cả mười luật (hoặc không đạt luật 3 khi {@code onboardKg} vượt tải). */
    private PickupContextBuilder.Built built(double onboardKg, int reusedIndex) {
        var stops = List.of(
                new PickupRules.RouteStop("30", new Point(10.80, 106.75), false, 5, true),
                new PickupRules.RouteStop("31", new Point(10.80, 106.80), false, 3, true));
        var request = new PickupRules.Request(new Point(10.80, 106.72), new Point(10.80, 106.77), null,
                List.of(new PickupRules.Cargo(50, 50, 50, 50, "STANDARD")));
        var context = new PickupRules.Context(request, new PickupRules.VehicleLimits(1000, 600, 240, 240, null, null, null),
                new Point(10.80, 106.70), LocalDateTime.of(2026, 11, 6, 8, 0), stops, onboardKg, packing(List.of()),
                "STANDARD", null, (a, b) -> 600);
        return new PickupContextBuilder.Built(context, new ArrayList<>(List.of(current, next)),
                new PickupRules.Insertion(0, 1, reusedIndex));
    }

    private static CreatePickupRequest body() {
        return CreatePickupRequest.builder()
                .pickup(PickupPointRequest.builder().name("Kho Tân Uyên").address("Tân Uyên").latitude(BigDecimal.valueOf(10.80))
                        .longitude(BigDecimal.valueOf(106.72)).build())
                .delivery(PickupPointRequest.builder().name("Bình Dương").address("Thủ Dầu Một")
                        .latitude(BigDecimal.valueOf(10.80)).longitude(BigDecimal.valueOf(106.77)).build())
                .packages(List.of(PickupPackageRequest.builder().packageCode("SHIP-9").lengthCm(BigDecimal.valueOf(50))
                        .widthCm(BigDecimal.valueOf(50)).heightCm(BigDecimal.valueOf(50)).weightKg(BigDecimal.valueOf(50))
                        .handlingClass(HandlingClass.STANDARD).build()))
                .build();
    }

    private PickupRequest stored(PickupRequestStatus status) {
        PickupRequest request = PickupRequest.builder().id(7L).trip(trip).company(trip.getCompany()).status(status)
                .pickupName("Kho Tân Uyên").pickupAddress("Tân Uyên").deliveryName("Bình Dương").deliveryAddress("Thủ Dầu Một")
                .deadline(LocalDateTime.of(2026, 11, 6, 12, 0)).build();
        request.getPackages().add(PickupRequestPackage.builder().pickupRequest(request).lineIndex(0).packageCode("SHIP-9")
                .lengthCm(BigDecimal.valueOf(50)).widthCm(BigDecimal.valueOf(50)).heightCm(BigDecimal.valueOf(50))
                .weightKg(BigDecimal.valueOf(50)).handlingClass(HandlingClass.STANDARD).build());
        lenient().when(pickupRequestRepository.findByIdAndTripId(7L, 10L)).thenReturn(Optional.of(request));
        return request;
    }

    @Test
    void ownDriverCreatesRequestThatPassesAllRules() {
        signIn(driver, "DRIVER");
        when(contextBuilder.build(eq(trip), any(PickupRequest.class), isNull())).thenReturn(built(900, -1));

        var response = service.create("10", body());

        assertEquals("VALIDATED", response.getStatus());
        assertEquals(10, response.getValidationResults().size());
        assertEquals("SHIP-9", response.getPackages().getFirst().getPackageCode());
    }

    @Test
    void failingRulesKeepRequestPending() {
        signIn(dispatcher, "DISPATCHER");
        when(contextBuilder.build(eq(trip), any(PickupRequest.class), isNull())).thenReturn(built(990, -1));

        assertEquals("PENDING", service.create("10", body()).getStatus());
    }

    @Test
    void driverOfAnotherTripCannotCreate() {
        User other = User.builder().id(99L).company(trip.getCompany()).build();
        signIn(other, "DRIVER");

        AppException error = assertThrows(AppException.class, () -> service.create("10", body()));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
        verify(pickupRequestRepository, never()).save(any());
    }

    @Test
    void tripNotInTransitIsRejected() {
        signIn(dispatcher, "DISPATCHER");
        trip.setStatus(TripStatus.LOADING);

        AppException error = assertThrows(AppException.class, () -> service.create("10", body()));
        assertEquals(ErrorCode.PICKUP_TRIP_NOT_IN_TRANSIT, error.getErrorCode());
    }

    @Test
    void approvingAFailingRequestNeedsAReason() {
        signIn(dispatcher, "DISPATCHER");
        stored(PickupRequestStatus.PENDING);
        when(contextBuilder.build(eq(trip), any(PickupRequest.class), eq(""))).thenReturn(built(990, -1));

        AppException error = assertThrows(AppException.class, () -> service.approve("10", 7L, null));
        assertEquals(ErrorCode.PICKUP_REASON_REQUIRED, error.getErrorCode());
        verify(cargoPackageRepository, never()).save(any());
    }

    @Test
    void approvingCreatesPackagesAndInsertsTwoStopsAfterTheCurrentOne() {
        signIn(dispatcher, "DISPATCHER");
        PickupRequest request = stored(PickupRequestStatus.VALIDATED);
        when(contextBuilder.build(eq(trip), any(PickupRequest.class), eq(""))).thenReturn(built(900, -1));

        var response = service.approve("10", 7L, null);

        assertEquals("APPROVED", response.getStatus());
        CargoPackage cargo = request.getPackages().getFirst().getCargoPackage();
        assertNotNull(cargo);
        assertEquals("SHIP-9", cargo.getPackageCode());
        assertEquals(PackageStatus.PLANNED, cargo.getStatus());
        assertEquals("Thủ Dầu Một", cargo.getDestination());
        assertEquals(DeliveryStopType.PICKUP, request.getPickupStop().kind());
        assertEquals(2, request.getPickupStop().getStopSequence());
        assertEquals(3, request.getDeliveryStop().getStopSequence());
        assertEquals(DeliveryStopType.DELIVERY, request.getDeliveryStop().kind());
        assertEquals(4, next.getStopSequence());
        assertNull(request.getOverrideReason());
        assertNotNull(request.getLayout());
        assertEquals(dispatcher, request.getApprovedBy());
    }

    @Test
    void approvingWithReuseKeepsTheExistingDeliveryStopAndTakesTheEarlierDeadline() {
        signIn(dispatcher, "DISPATCHER");
        PickupRequest request = stored(PickupRequestStatus.PENDING);
        when(contextBuilder.build(eq(trip), any(PickupRequest.class), eq("Khách cần gấp"))).thenReturn(built(990, 1));

        service.approve("10", 7L, ApprovePickupRequest.builder().overrideReason("Khách cần gấp").build());

        assertSame(next, request.getDeliveryStop());
        assertEquals(3, next.getStopSequence());
        assertEquals(LocalDateTime.of(2026, 11, 6, 12, 0), next.getPlannedArrival());
        assertEquals("Khách cần gấp", request.getOverrideReason());
    }

    @Test
    void rejectNeedsAReasonAndOnlyOpenRequests() {
        signIn(dispatcher, "DISPATCHER");
        stored(PickupRequestStatus.PENDING);

        AppException missing = assertThrows(AppException.class, () -> service.reject("10", 7L, null));
        assertEquals(ErrorCode.PICKUP_REASON_REQUIRED, missing.getErrorCode());

        var response = service.reject("10", 7L, RejectPickupRequest.builder().reason("Xe đầy").build());
        assertEquals("REJECTED", response.getStatus());
        assertEquals("Xe đầy", response.getRejectReason());

        AppException again = assertThrows(AppException.class,
                () -> service.reject("10", 7L, RejectPickupRequest.builder().reason("Xe đầy").build()));
        assertEquals(ErrorCode.INVALID_PICKUP_STATUS_TRANSITION, again.getErrorCode());
    }

    @Test
    void driverCannotApprove() {
        signIn(driver, "DRIVER");
        stored(PickupRequestStatus.VALIDATED);

        AppException error = assertThrows(AppException.class, () -> service.approve("10", 7L, null));
        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
    }
}
