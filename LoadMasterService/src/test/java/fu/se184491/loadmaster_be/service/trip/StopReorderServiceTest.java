package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.DeliveryStopStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.pickup.PickupRequestRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StopReorderServiceTest {

    @Mock private CurrentUserService currentUserService;
    @Mock private TripLookupHelper tripLookupHelper;
    @Mock private DeliveryStopRepository deliveryStopRepository;
    @Mock private PickupRequestRepository pickupRequestRepository;
    @Mock private LoadingExecutionRepository loadingExecutionRepository;
    @Mock private PackagePlacementRepository packagePlacementRepository;
    @Mock private PlacementConfirmationRepository placementConfirmationRepository;
    @Mock private DeviationRepository deviationRepository;
    @Mock private UnloadConfirmationRepository unloadConfirmationRepository;
    @Mock private TripPackageResolver tripPackageResolver;
    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks private StopReorderService service;

    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = Trip.builder().id(10L).tripCode("TRIP-10").status(TripStatus.IN_TRANSIT).build();
        when(currentUserService.getCurrentUser()).thenReturn(User.builder().id(2L).build());
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripLookupHelper.requireTrip("TRIP-10", 1L)).thenReturn(trip);
    }

    @Test
    void stopStillToVisitWithoutCoordinatesIsRejectedBeforeAnythingChanges() {
        DeliveryStop done = stop(31L, 1, DeliveryStopStatus.COMPLETED, false);
        DeliveryStop located = stop(32L, 2, DeliveryStopStatus.PENDING, true);
        DeliveryStop missing = stop(33L, 3, DeliveryStopStatus.PENDING, false);
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(done, located, missing));

        AppException error = assertThrows(AppException.class,
                () -> service.reorder("TRIP-10", List.of(31L, 33L, 32L)));

        assertEquals(ErrorCode.MISSING_STOP_COORDINATES, error.getErrorCode());
        assertEquals(Map.of("stopIds", List.of("33")), error.getDetails());
        assertEquals(3, missing.getStopSequence());
        verify(deliveryStopRepository, never()).saveAllAndFlush(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void completedOrReachedStopsDoNotNeedCoordinates() {
        DeliveryStop done = stop(31L, 1, DeliveryStopStatus.COMPLETED, false);
        DeliveryStop reached = stop(32L, 2, DeliveryStopStatus.PENDING, false);
        reached.setActualArrival(LocalDateTime.of(2026, 11, 6, 9, 0));
        DeliveryStop next = stop(33L, 3, DeliveryStopStatus.PENDING, true);
        when(deliveryStopRepository.findByTripIdOrderByStopSequenceAsc(10L)).thenReturn(List.of(done, reached, next));

        // Qua được bước toạ độ; thứ tự không đổi gì nên bị luật thứ tự từ chối
        AppException error = assertThrows(AppException.class,
                () -> service.reorder("TRIP-10", List.of(31L, 32L, 33L)));

        assertEquals(ErrorCode.STOP_ORDER_INVALID, error.getErrorCode());
    }

    private DeliveryStop stop(Long id, int sequence, DeliveryStopStatus status, boolean located) {
        DeliveryStop stop = DeliveryStop.builder().id(id).trip(trip).stopSequence(sequence).status(status).build();
        if (located) {
            stop.setLatitude(new BigDecimal("10.7769000"));
            stop.setLongitude(new BigDecimal("106.7009000"));
        }
        return stop;
    }
}
