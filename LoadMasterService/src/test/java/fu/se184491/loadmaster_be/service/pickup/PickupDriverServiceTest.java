package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.constant.cargo.LabelMethod;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.constant.pickup.PickupRequestStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.DeliveryStopType;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
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
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestPackageRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupDriverServiceTest {

    @Mock private PickupRequestRepository pickupRequestRepository;
    @Mock private PickupRequestPackageRepository pickupRequestPackageRepository;
    @Mock private CargoPackageRepository cargoPackageRepository;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private CurrentUserService currentUserService;

    @InjectMocks
    private PickupDriverService service;

    private User driver;
    private Trip trip;
    private DeliveryStop pickupStop;
    private DeliveryStop deliveryStop;
    private PickupRequest request;
    private PickupRequestPackage line;
    private CargoPackage cargo;

    @BeforeEach
    void setUp() {
        Company company = Company.builder().id(1L).build();
        driver = User.builder().id(8L).company(company).build();
        trip = Trip.builder().id(10L).company(company).driver(driver).status(TripStatus.IN_TRANSIT).build();
        pickupStop = DeliveryStop.builder().id(40L).trip(trip).stopSequence(2).stopType(DeliveryStopType.PICKUP)
                .status(DeliveryStopStatus.ARRIVED).actualArrival(LocalDateTime.of(2026, 11, 6, 9, 0)).build();
        deliveryStop = DeliveryStop.builder().id(41L).trip(trip).stopSequence(3).stopType(DeliveryStopType.DELIVERY)
                .status(DeliveryStopStatus.PENDING).build();
        cargo = CargoPackage.builder().id(500L).packageCode("SHIP-9").trackingBarcode("SHIP-9").qrToken("qr-9")
                .status(PackageStatus.PLANNED).build();
        request = PickupRequest.builder().id(7L).trip(trip).status(PickupRequestStatus.APPROVED).pickupStop(pickupStop)
                .deliveryStop(deliveryStop).build();
        line = PickupRequestPackage.builder().id(70L).pickupRequest(request).lineIndex(0).packageCode("SHIP-9")
                .weightKg(BigDecimal.TEN).cargoPackage(cargo).build();
        request.getPackages().add(line);
        lenient().when(currentUserService.getCurrentUser()).thenReturn(driver);
        lenient().when(pickupRequestPackageRepository.findByCargoPackageId(500L)).thenReturn(Optional.of(line));
        lenient().when(pickupRequestRepository.findByPickupStopId(40L)).thenReturn(List.of(request));
    }

    @Test
    void pickupStopListsPackagesToPickUp() {
        var rows = service.pickupPackagesAt(pickupStop);

        assertEquals(1, rows.size());
        assertEquals("qr-9", rows.getFirst().getQrToken());
        assertEquals(7L, rows.getFirst().getPickupRequestId());
        assertFalse(rows.getFirst().getPicked());
        assertTrue(service.pickupPackagesAt(deliveryStop).isEmpty());
    }

    @Test
    void driverPicksPackageAtTheCurrentPickupStop() {
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(pickupStop));

        service.pick(500L);

        assertNotNull(line.getPickedAt());
        assertEquals(PackageStatus.LOADED, cargo.getStatus());
        assertEquals(LabelMethod.QR, line.getPickMethod());
        AppException again = assertThrows(AppException.class, () -> service.pick(500L));
        assertEquals(ErrorCode.PICKUP_PACKAGE_ALREADY_PICKED, again.getErrorCode());
    }

    @Test
    void pickByTypedCodeRecordsTheMethod() {
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(pickupStop));

        service.pick(500L, LabelMethod.CODE);

        assertEquals(LabelMethod.CODE, line.getPickMethod());
    }

    @Test
    void pickingAwayFromThePickupStopIsRejected() {
        DeliveryStop elsewhere = DeliveryStop.builder().id(39L).trip(trip).stopSequence(1).status(DeliveryStopStatus.ARRIVED)
                .build();
        when(deliveryStopRepository.findFirstByTripIdAndStatusNotOrderByStopSequenceAsc(10L, DeliveryStopStatus.COMPLETED))
                .thenReturn(Optional.of(elsewhere));

        AppException error = assertThrows(AppException.class, () -> service.pick(500L));
        assertEquals(ErrorCode.PICKUP_PACKAGE_NOT_AT_STOP, error.getErrorCode());
    }

    @Test
    void pickupStopCompletesOnlyWhenEveryPackageIsOnBoard() {
        AppException error = assertThrows(AppException.class, () -> service.requireComplete(pickupStop));
        assertEquals(ErrorCode.PICKUP_STOP_INCOMPLETE, error.getErrorCode());

        line.setPickedAt(LocalDateTime.of(2026, 11, 6, 9, 5));
        assertDoesNotThrow(() -> service.requireComplete(pickupStop));
        assertDoesNotThrow(() -> service.requireComplete(deliveryStop));
    }

    @Test
    void completingStopsMovesRequestToLoadedThenDelivered() {
        line.setPickedAt(LocalDateTime.of(2026, 11, 6, 9, 5));
        service.onStopCompleted(pickupStop, driver);
        assertEquals(PickupRequestStatus.LOADED, request.getStatus());

        when(pickupRequestRepository.findByDeliveryStopIdAndStatusIn(any(), any())).thenReturn(List.of(request));
        cargo.setStatus(PackageStatus.LOADED);
        service.onStopCompleted(deliveryStop, driver);
        assertEquals(PickupRequestStatus.LOADED, request.getStatus());

        cargo.setStatus(PackageStatus.DELIVERED);
        service.onStopCompleted(deliveryStop, driver);
        assertEquals(PickupRequestStatus.DELIVERED, request.getStatus());
        assertNotNull(request.getDeliveredAt());
    }
}
