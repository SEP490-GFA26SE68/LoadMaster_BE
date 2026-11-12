package fu.se184491.loadmaster_be.service.monitoring;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.monitoring.DriverLocationRequest;
import fu.se184491.loadmaster_be.dto.response.monitoring.LocationPointResponse;
import fu.se184491.loadmaster_be.entity.monitoring.GpsTracking;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.monitoring.GpsTrackingRepository;
import fu.se184491.loadmaster_be.service.monitoring.Impl.GpsTrackingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GpsTrackingServiceImplTest {

    @Mock private GpsTrackingRepository gpsTrackingRepository;
    @Mock private TripLookupHelper tripLookupHelper;
    @Mock private EtaCalculationService etaCalculationService;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks private GpsTrackingServiceImpl service;

    private DriverLocationRequest request() {
        DriverLocationRequest request = new DriverLocationRequest();
        request.setTripId("TRIP-1");
        request.setLat(10.762622);
        request.setLng(106.660172);
        return request;
    }

    private Trip trip(TripStatus status) {
        Trip trip = Trip.builder().id(5L).status(status).build();
        when(tripLookupHelper.requireTripByIdOrCode("TRIP-1")).thenReturn(trip);
        return trip;
    }

    @Test
    void anEtaFailureKeepsTheCommittedGpsPoint() {
        trip(TripStatus.IN_TRANSIT);
        TransactionStatus gpsTx = mock(TransactionStatus.class);
        TransactionStatus etaTx = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(gpsTx, etaTx);
        when(gpsTrackingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("routing down"))
                .when(etaCalculationService).recalculateAndAlert(any(), any(GpsTracking.class));

        LocationPointResponse point = service.recordLocation(request());

        assertEquals("GPS", point.getSource());
        InOrder order = inOrder(transactionManager, etaCalculationService);
        order.verify(transactionManager).commit(gpsTx);
        order.verify(etaCalculationService).recalculateAndAlert(any(), any(GpsTracking.class));
        order.verify(transactionManager).rollback(etaTx);
        verify(transactionManager, never()).rollback(gpsTx);
        verify(transactionManager).getTransaction(argThat(def -> def != null
                && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void aTripNotInTransitSavesNothing() {
        trip(TripStatus.DELIVERED);

        AppException error = assertThrows(AppException.class, () -> service.recordLocation(request()));

        assertEquals(ErrorCode.GPS_TRIP_NOT_IN_TRANSIT, error.getErrorCode());
        verify(gpsTrackingRepository, never()).save(any());
        verify(etaCalculationService, never()).recalculateAndAlert(any(), any());
    }
}
