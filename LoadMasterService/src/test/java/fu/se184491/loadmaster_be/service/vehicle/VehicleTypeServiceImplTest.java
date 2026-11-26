package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.vehicle.CreateVehicleTypeRequest;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleTypeRepository;
import fu.se184491.loadmaster_be.service.vehicle.Impl.VehicleTypeServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VehicleTypeServiceImplTest {

    @Mock private VehicleTypeRepository vehicleTypeRepository;
    @Mock private EntityManager entityManager;
    @Mock private CurrentUserService currentUserService;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private TripRepository tripRepository;
    @Mock private LoadPlanRepository loadPlanRepository;
    @InjectMocks private VehicleTypeServiceImpl service;

    private VehicleType type;

    @BeforeEach
    void setUp() {
        type = VehicleType.builder().id(3L).company(Company.builder().id(1L).build()).name("Thùng 6 m")
                .innerLength(600).innerWidth(235).innerHeight(240).doorWidth(230).doorHeight(230)
                .maxPayloadKg(new BigDecimal("5000.00")).build();
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(vehicleTypeRepository.findByIdAndCompanyId(3L, 1L)).thenReturn(Optional.of(type));
        when(vehicleTypeRepository.save(any(VehicleType.class))).thenAnswer(call -> call.getArgument(0));
        when(tripRepository.findByVehicle_VehicleType_IdAndStatusIn(3L, List.of(TripStatus.DRAFT, TripStatus.PLANNED)))
                .thenReturn(List.of(Trip.builder().id(40L).build()));
    }

    private static CreateVehicleTypeRequest request(String name, int length, String payload) {
        return new CreateVehicleTypeRequest(name, length, 235, 240, 230, 230, new BigDecimal(payload),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Sáu thông số trục theo thứ tự: giới hạn trước, giới hạn sau, vị trí trước, vị trí sau, tải rỗng trước, tải rỗng sau. */
    private static CreateVehicleTypeRequest withAxles(String frontLimit, String rearLimit, String frontX, String rearX,
                                                      String emptyFront, String emptyRear) {
        return new CreateVehicleTypeRequest("Thùng 6 m", 600, 235, 240, 230, 230, new BigDecimal("5000"),
                dec(frontLimit), dec(rearLimit), dec(frontX), dec(rearX), dec(emptyFront), dec(emptyRear),
                null, null, null, null, null);
    }

    private static CreateVehicleTypeRequest withPhysics(String cogY, String floorLoad) {
        return new CreateVehicleTypeRequest("Thùng 6 m", 600, 235, 240, 230, 230, new BigDecimal("5000"),
                null, null, null, null, null, null, null, dec(cogY), dec(floorLoad), null, null);
    }

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Test
    void allSixAxleNumbersAreStoredAndReturned() {
        var response = service.updateVehicleType(3L, withAxles("3500", "6500", "-80", "420", "1800", "1400"));

        assertEquals(new BigDecimal("-80"), type.getFrontAxleX());
        assertEquals(new BigDecimal("420"), type.getRearAxleX());
        assertEquals(new BigDecimal("1800"), type.getEmptyFrontAxleLoadKg());
        assertEquals(new BigDecimal("1400"), type.getEmptyRearAxleLoadKg());
        assertEquals(new BigDecimal("420"), response.rearAxleX());
        assertEquals(new BigDecimal("1400"), response.emptyRearAxleLoadKg());
        verify(loadPlanRepository).markStaleByTripId(40L);
    }

    @Test
    void noAxleNumbersAtAllIsAccepted() {
        service.updateVehicleType(3L, withAxles(null, null, null, null, null, null));

        assertEquals(null, type.getFrontAxleX());
        assertEquals(null, type.getRearAxleLimitKg());
    }

    @Test
    void onlyTheTwoAxleLimitsIsRejected() {
        AppException error = assertThrows(AppException.class,
                () -> service.updateVehicleType(3L, withAxles("3500", "6500", null, null, null, null)));

        assertEquals(ErrorCode.VEHICLE_AXLE_CONFIG_INCOMPLETE, error.getErrorCode());
        verify(vehicleTypeRepository, never()).save(any());
    }

    @Test
    void rearAxleMustBeBehindTheFrontAxle() {
        AppException error = assertThrows(AppException.class,
                () -> service.updateVehicleType(3L, withAxles("3500", "6500", "420", "420", "1800", "1400")));

        assertEquals(ErrorCode.VEHICLE_AXLE_ORDER, error.getErrorCode());
    }

    @Test
    void changingTheBoxMarksPlansOfUnstartedTripsStale() {
        service.updateVehicleType(3L, request("Thùng 6 m", 620, "5000"));

        assertEquals(620, type.getInnerLength());
        verify(loadPlanRepository).markStaleByTripId(40L);
    }

    @Test
    void renamingOrRewritingTheSameNumbersKeepsPlansFresh() {
        // 5000 và 5000.00 là cùng một tải trọng
        service.updateVehicleType(3L, request("Thùng 6 m (mới)", 600, "5000"));

        assertEquals("Thùng 6 m (mới)", type.getName());
        verify(loadPlanRepository, never()).markStaleByTripId(anyLong());
    }

    @Test
    void floorLoadAndLateralCogLimitAreStoredAndChangingThemMarksPlansStale() {
        var response = service.updateVehicleType(3L, withPhysics("0.10", "2500"));

        assertEquals(new BigDecimal("0.10"), type.getMaxCogOffsetRatioY());
        assertEquals(new BigDecimal("2500"), type.getFloorLoadCapacityKgM2());
        assertEquals(new BigDecimal("2500"), response.floorLoadCapacityKgM2());
        verify(loadPlanRepository).markStaleByTripId(40L);
    }

    @Test
    void oldClientsThatDoNotSendThePhysicsNumbersKeepThem() {
        type.setMaxCogOffsetRatioY(new BigDecimal("0.10"));
        type.setFloorLoadCapacityKgM2(new BigDecimal("2500"));

        service.updateVehicleType(3L, request("Thùng 6 m", 600, "5000"));

        assertEquals(new BigDecimal("0.10"), type.getMaxCogOffsetRatioY());
        assertEquals(new BigDecimal("2500"), type.getFloorLoadCapacityKgM2());
        verify(loadPlanRepository, never()).markStaleByTripId(anyLong());
    }

    @Test
    void typeWithVehiclesCannotBeDeleted() {
        when(vehicleRepository.countByVehicleTypeId(3L)).thenReturn(2L);

        AppException error = assertThrows(AppException.class, () -> service.deleteVehicleType(3L));

        assertEquals(ErrorCode.VEHICLE_TYPE_IN_USE, error.getErrorCode());
        verify(vehicleTypeRepository, never()).delete(any());
    }

    @Test
    void unusedTypeIsDeleted() {
        service.deleteVehicleType(3L);

        verify(vehicleTypeRepository).delete(type);
    }
}
