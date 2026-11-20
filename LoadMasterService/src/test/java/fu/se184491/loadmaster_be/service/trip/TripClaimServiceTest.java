package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.trip.RejectTripClaimRequest;
import fu.se184491.loadmaster_be.dto.request.trip.TripClaimRequest;
import fu.se184491.loadmaster_be.dto.response.trip.OpenTripResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimInboxResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripClaimResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.trip.TripClaim;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripClaimRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.trip.impl.TripClaimServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripClaimServiceTest {

    @Mock
    private TripRepository tripRepository;

    @Mock
    private TripClaimRepository tripClaimRepository;

    @Mock
    private DeliveryStopRepository deliveryStopRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private TripClaimServiceImpl tripClaimService;

    private Company company1;
    private Company company2;
    private User driver1;
    private User driver2;
    private User dispatcher;
    private Trip openTrip1;
    private Trip openTrip2;
    private Trip tripWithDriver;
    private Trip tripInTransit;
    private Trip tripCompany2;

    @BeforeEach
    void setUp() {
        company1 = Company.builder().id(1L).build();
        company2 = Company.builder().id(2L).build();

        driver1 = User.builder().id(101L).company(company1).fullName("Tài xế Nguyễn Văn A").phoneNumber("0912345678").build();
        driver2 = User.builder().id(102L).company(company1).fullName("Tài xế Trần Văn B").phoneNumber("0987654321").build();
        dispatcher = User.builder().id(201L).company(company1).fullName("Điều phối viên C").build();

        CompanyWarehouse warehouse = CompanyWarehouse.builder().id(10L).warehouseName("Kho Tổng Hà Nội").build();

        openTrip1 = Trip.builder()
                .id(1L)
                .tripCode("TRIP-001")
                .tripName("Chuyến Hà Nội - Bắc Ninh")
                .company(company1)
                .originWarehouse(warehouse)
                .status(TripStatus.DRAFT)
                .driver(null)
                .departureTime(LocalDateTime.now().plusHours(2))
                .build();

        openTrip2 = Trip.builder()
                .id(2L)
                .tripCode("TRIP-002")
                .tripName("Chuyến Hà Nội - Hải Phòng")
                .company(company1)
                .originName("Kho Cảng")
                .status(TripStatus.PLANNED)
                .driver(null)
                .departureTime(LocalDateTime.now().plusHours(4))
                .build();

        tripWithDriver = Trip.builder()
                .id(3L)
                .tripCode("TRIP-003")
                .tripName("Chuyến đã có tài xế")
                .company(company1)
                .status(TripStatus.PLANNED)
                .driver(driver2)
                .build();

        tripInTransit = Trip.builder()
                .id(4L)
                .tripCode("TRIP-004")
                .tripName("Chuyến đang chạy")
                .company(company1)
                .status(TripStatus.IN_TRANSIT)
                .driver(null)
                .build();

        tripCompany2 = Trip.builder()
                .id(5L)
                .tripCode("TRIP-005")
                .tripName("Chuyến công ty khác")
                .company(company2)
                .status(TripStatus.DRAFT)
                .driver(null)
                .build();
    }

    // -------------------------------------------------------------------------
    // 1. GET /api/driver/open-trips
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Chỉ trả chuyến DRAFT/PLANNED chưa có tài xế, cùng công ty; chuyến công ty khác không lọt ra")
    void getOpenTrips_returnsOnlyDraftAndPlannedWithoutDriverInSameCompany() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        // Repo trả về các chuyến mở của công ty 1
        when(tripRepository.findByCompanyId(1L)).thenReturn(List.of(openTrip1, openTrip2, tripWithDriver, tripInTransit));
        when(deliveryStopRepository.countStopsByTripIds(any())).thenReturn(List.of(
                new Object[]{1L, 3L},
                new Object[]{2L, 5L}
        ));
        when(tripClaimRepository.findByDriverIdAndCompanyId(101L, 1L)).thenReturn(Collections.emptyList());

        List<OpenTripResponse> result = tripClaimService.getOpenTrips();

        assertEquals(2, result.size());
        assertEquals("TRIP-001", result.get(0).tripCode());
        assertEquals(3, result.get(0).stopCount());
        assertNull(result.get(0).myClaimStatus());
        assertEquals("Kho Tổng Hà Nội", result.get(0).originWarehouse());

        assertEquals("TRIP-002", result.get(1).tripCode());
        assertEquals(5, result.get(1).stopCount());
        assertNull(result.get(1).myClaimStatus());
        assertEquals("Kho Cảng", result.get(1).originWarehouse());
    }

    @Test
    @DisplayName("getOpenTrips trả về đúng myClaimStatus khi tài xế đã xin chuyến")
    void getOpenTrips_includesMyClaimStatus() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        when(tripRepository.findByCompanyId(1L)).thenReturn(List.of(openTrip1));
        when(deliveryStopRepository.countStopsByTripIds(any())).thenReturn(Collections.singletonList(new Object[]{1L, 2L}));

        TripClaim claim = TripClaim.builder()
                .id(50L)
                .trip(openTrip1)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();
        when(tripClaimRepository.findByDriverIdAndCompanyId(101L, 1L)).thenReturn(List.of(claim));

        List<OpenTripResponse> result = tripClaimService.getOpenTrips();

        assertEquals(1, result.size());
        assertEquals(TripClaimStatus.PENDING, result.get(0).myClaimStatus());
    }

    @Test
    @DisplayName("Hộp thư phiếu xin chuyến ánh xạ thông tin chuyến, kho, tài xế và điểm giao")
    void listPendingClaims_mapsClaimAndTripFieldsAndFiltersByCurrentCompany() {
        LocalDateTime departureTime = LocalDateTime.of(2026, 11, 21, 8, 30, 0);
        LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 11, 20, 21, 5, 33);
        LocalDateTime secondCreatedAt = LocalDateTime.of(2026, 11, 20, 21, 7, 10);
        CompanyWarehouse warehouse = CompanyWarehouse.builder()
                .id(30L)
                .warehouseName("Warehouse North")
                .build();
        Trip warehouseTrip = Trip.builder()
                .id(301L)
                .tripCode("TRIP-301")
                .tripName("North route")
                .departureTime(departureTime)
                .originWarehouse(warehouse)
                .originName("Ignored origin")
                .company(company1)
                .status(TripStatus.DRAFT)
                .build();
        Trip fallbackTrip = Trip.builder()
                .id(302L)
                .tripCode("TRIP-302")
                .tripName("Port route")
                .departureTime(departureTime.plusHours(1))
                .originName("Port warehouse")
                .company(company1)
                .status(TripStatus.PLANNED)
                .build();
        TripClaim firstClaim = TripClaim.builder()
                .id(801L)
                .trip(warehouseTrip)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .note("First claim note")
                .createdAt(firstCreatedAt)
                .build();
        TripClaim secondClaim = TripClaim.builder()
                .id(802L)
                .trip(fallbackTrip)
                .driver(driver2)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .note("Second claim note")
                .createdAt(secondCreatedAt)
                .build();
        List<TripClaim> claims = List.of(firstClaim, secondClaim);

        TripClaimRepository inboxRepository = mock(TripClaimRepository.class, invocation ->
                "findByCompanyIdAndStatusAndTripDriverIsNullAndTripStatusInOrderByCreatedAtAscIdAsc"
                        .equals(invocation.getMethod().getName())
                        ? claims
                        : Answers.RETURNS_DEFAULTS.answer(invocation));
        DeliveryStopRepository stopRepository = mock(DeliveryStopRepository.class, invocation -> {
            if ("countStopsByTripIds".equals(invocation.getMethod().getName())) {
                return List.<Object[]>of(new Object[]{301L, 4L}, new Object[]{302L, 2L});
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        CurrentUserService currentUser = mock(CurrentUserService.class, invocation ->
                "getCurrentCompanyId".equals(invocation.getMethod().getName())
                        ? 1L
                        : Answers.RETURNS_DEFAULTS.answer(invocation));
        TripClaimServiceImpl service = new TripClaimServiceImpl(tripRepository, inboxRepository, stopRepository, currentUser);

        List<TripClaimInboxResponse> result = service.listPendingClaims();

        assertEquals(2, result.size());
        TripClaimInboxResponse first = result.get(0);
        assertEquals(801L, first.claimId());
        assertEquals(301L, first.tripId());
        assertEquals("TRIP-301", first.tripCode());
        assertEquals("North route", first.tripName());
        assertEquals(departureTime, first.departureTime());
        assertEquals("Warehouse North", first.originWarehouse());
        assertEquals(4, first.stopCount());
        assertEquals(driver1.getId(), first.driverUserId());
        assertEquals(driver1.getFullName(), first.driverName());
        assertEquals(driver1.getPhoneNumber(), first.driverPhone());
        assertEquals("First claim note", first.note());
        assertEquals(firstCreatedAt, first.createdAt());

        TripClaimInboxResponse second = result.get(1);
        assertEquals(802L, second.claimId());
        assertEquals(302L, second.tripId());
        assertEquals("TRIP-302", second.tripCode());
        assertEquals("Port route", second.tripName());
        assertEquals("Port warehouse", second.originWarehouse());
        assertEquals(2, second.stopCount());
        assertEquals(driver2.getFullName(), second.driverName());
        assertEquals(driver2.getPhoneNumber(), second.driverPhone());
        assertEquals("Second claim note", second.note());
        assertEquals(secondCreatedAt, second.createdAt());

        verify(currentUser).getCurrentCompanyId();
        verify(inboxRepository).findByCompanyIdAndStatusAndTripDriverIsNullAndTripStatusInOrderByCreatedAtAscIdAsc(
                eq(1L), eq(TripClaimStatus.PENDING), eq(List.of(TripStatus.DRAFT, TripStatus.PLANNED)));
        verify(stopRepository).countStopsByTripIds(eq(List.of(301L, 302L)));
    }

    @Test
    @DisplayName("Hộp thư phiếu xin chuyến trả danh sách rỗng khi không có phiếu")
    void listPendingClaims_returnsEmptyWhenNoClaims() {
        List<TripClaimInboxResponse> result = tripClaimService.listPendingClaims();

        assertTrue(result.isEmpty());
        verifyNoInteractions(deliveryStopRepository);
    }

    // -------------------------------------------------------------------------
    // 2. Gửi phiếu xin chuyến: lần đầu thành công (PENDING)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Gửi phiếu lần đầu thành công, phiếu ở PENDING")
    void claimTrip_success_whenFirstTime() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripRepository.findByIdAndCompanyId(1L, 1L)).thenReturn(Optional.of(openTrip1));
        when(tripClaimRepository.existsByTripIdAndDriverIdAndStatus(1L, 101L, TripClaimStatus.PENDING)).thenReturn(false);

        when(tripClaimRepository.save(any(TripClaim.class))).thenAnswer(invocation -> {
            TripClaim saved = invocation.getArgument(0);
            saved.setId(10L);
            saved.setCreatedAt(LocalDateTime.now());
            return saved;
        });

        TripClaimResponse response = tripClaimService.claimTrip(1L, new TripClaimRequest("Tôi nhận chuyến này nhé"));

        assertNotNull(response);
        assertEquals(TripClaimStatus.PENDING, response.status());
        assertEquals(1L, response.tripId());
        assertEquals(101L, response.driverUserId());
        assertEquals("Tôi nhận chuyến này nhé", response.note());
    }

    // -------------------------------------------------------------------------
    // 3. Gửi lần hai khi phiếu cũ còn chờ -> TRIP_CLAIM_DUPLICATE
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Gửi lần hai khi phiếu cũ còn chờ -> TRIP_CLAIM_DUPLICATE")
    void claimTrip_throwsDuplicate_whenAlreadyPending() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripRepository.findByIdAndCompanyId(1L, 1L)).thenReturn(Optional.of(openTrip1));
        when(tripClaimRepository.existsByTripIdAndDriverIdAndStatus(1L, 101L, TripClaimStatus.PENDING)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.claimTrip(1L, new TripClaimRequest(null)));

        assertEquals(ErrorCode.TRIP_CLAIM_DUPLICATE, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    @Test
    @DisplayName("claimTrip khi chuyến đã có tài xế -> TRIP_CLAIM_TRIP_TAKEN")
    void claimTrip_throwsTripTaken_whenTripAlreadyHasDriver() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripRepository.findByIdAndCompanyId(3L, 1L)).thenReturn(Optional.of(tripWithDriver));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.claimTrip(3L, new TripClaimRequest(null)));

        assertEquals(ErrorCode.TRIP_CLAIM_TRIP_TAKEN, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    @Test
    @DisplayName("claimTrip khi chuyến ở IN_TRANSIT -> TRIP_CLAIM_TRIP_NOT_OPEN")
    void claimTrip_throwsTripNotOpen_whenTripNotInDraftOrPlanned() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripRepository.findByIdAndCompanyId(4L, 1L)).thenReturn(Optional.of(tripInTransit));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.claimTrip(4L, new TripClaimRequest(null)));

        assertEquals(ErrorCode.TRIP_CLAIM_TRIP_NOT_OPEN, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    @Test
    @DisplayName("claimTrip chuyến của công ty khác -> TRIP_NOT_FOUND (cách ly công ty)")
    void claimTrip_throwsNotFound_whenTripBelongsToAnotherCompany() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripRepository.findByIdAndCompanyId(5L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.claimTrip(5L, new TripClaimRequest(null)));

        assertEquals(ErrorCode.TRIP_NOT_FOUND, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 4. Rút phiếu của chính mình thành công; rút phiếu người khác -> TRIP_CLAIM_NOT_OWN
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Rút phiếu của chính mình thành công")
    void cancelClaim_success_whenOwnPendingClaim() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim claim = TripClaim.builder()
                .id(10L)
                .trip(openTrip1)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        when(tripClaimRepository.findByTripIdAndCompanyId(1L, 1L)).thenReturn(List.of(claim));
        when(tripClaimRepository.save(any(TripClaim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TripClaimResponse response = tripClaimService.cancelClaim(1L);

        assertEquals(TripClaimStatus.CANCELLED, response.status());
        assertEquals(driver1.getId(), response.decidedById());
        assertNotNull(response.decidedAt());
    }

    @Test
    @DisplayName("Rút phiếu người khác -> TRIP_CLAIM_NOT_OWN")
    void cancelClaim_throwsNotOwn_whenNotOwnClaim() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        // Phiếu của chuyến 1 nhưng do driver2 gửi
        TripClaim claimOfDriver2 = TripClaim.builder()
                .id(20L)
                .trip(openTrip1)
                .driver(driver2)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();

        when(tripClaimRepository.findByTripIdAndCompanyId(1L, 1L)).thenReturn(List.of(claimOfDriver2));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.cancelClaim(1L));

        assertEquals(ErrorCode.TRIP_CLAIM_NOT_OWN, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rút phiếu khi phiếu đã có quyết định -> TRIP_CLAIM_ALREADY_DECIDED")
    void cancelClaim_throwsAlreadyDecided_whenClaimAlreadyDecided() {
        when(currentUserService.getCurrentUser()).thenReturn(driver1);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim claim = TripClaim.builder()
                .id(10L)
                .trip(openTrip1)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.APPROVED)
                .build();

        when(tripClaimRepository.findByTripIdAndCompanyId(1L, 1L)).thenReturn(List.of(claim));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.cancelClaim(1L));

        assertEquals(ErrorCode.TRIP_CLAIM_ALREADY_DECIDED, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 5. Duyệt: gán driver, phiếu APPROVED, các phiếu PENDING khác thành REJECTED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Duyệt: chuyến có driver đúng người đó, phiếu APPROVED, phiếu PENDING khác của cùng chuyến thành REJECTED")
    void approveClaim_success() {
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim claimToApprove = TripClaim.builder()
                .id(10L)
                .trip(openTrip1)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        TripClaim otherPendingClaim = TripClaim.builder()
                .id(11L)
                .trip(openTrip1)
                .driver(driver2)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(claimToApprove));
        when(tripClaimRepository.findByTripIdAndCompanyIdAndStatus(1L, 1L, TripClaimStatus.PENDING))
                .thenReturn(List.of(otherPendingClaim));

        when(tripClaimRepository.save(any(TripClaim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TripClaimResponse response = tripClaimService.approveClaim(1L, 10L);

        // Kiểm tra gán driver vào chuyến
        assertEquals(driver1, openTrip1.getDriver());
        verify(tripRepository).save(openTrip1);

        // Kiểm tra phiếu duyệt
        assertEquals(TripClaimStatus.APPROVED, response.status());
        assertEquals(dispatcher.getId(), response.decidedById());
        assertNotNull(response.decidedAt());

        // Kiểm tra phiếu khác bị tự động từ chối
        assertEquals(TripClaimStatus.REJECTED, otherPendingClaim.getStatus());
        assertEquals(dispatcher, otherPendingClaim.getDecidedBy());
        assertNotNull(otherPendingClaim.getDecidedAt());
        assertNotNull(otherPendingClaim.getRejectReason());
        verify(tripClaimRepository).save(otherPendingClaim);
    }

    // -------------------------------------------------------------------------
    // 6. Duyệt khi chuyến đã có tài xế -> TRIP_CLAIM_TRIP_TAKEN
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Duyệt khi chuyến đã có tài xế -> TRIP_CLAIM_TRIP_TAKEN")
    void approveClaim_throwsTripTaken_whenTripAlreadyHasDriver() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        // Phiếu PENDING gửi cho chuyến đã có tài xế khác (tripWithDriver đã có driver2)
        TripClaim pendingClaim = TripClaim.builder()
                .id(10L)
                .trip(tripWithDriver)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(pendingClaim));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.approveClaim(3L, 10L));

        assertEquals(ErrorCode.TRIP_CLAIM_TRIP_TAKEN, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
        verify(tripRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 7. Duyệt khi chuyến ở IN_TRANSIT -> TRIP_CLAIM_TRIP_NOT_OPEN
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Duyệt khi chuyến ở IN_TRANSIT -> TRIP_CLAIM_TRIP_NOT_OPEN")
    void approveClaim_throwsTripNotOpen_whenTripNotInDraftOrPlanned() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        // Phiếu PENDING gửi cho chuyến ở trạng thái IN_TRANSIT
        TripClaim pendingClaim = TripClaim.builder()
                .id(10L)
                .trip(tripInTransit)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(pendingClaim));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.approveClaim(4L, 10L));

        assertEquals(ErrorCode.TRIP_CLAIM_TRIP_NOT_OPEN, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
        verify(tripRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 8. Duyệt hoặc từ chối một phiếu đã có quyết định -> TRIP_CLAIM_ALREADY_DECIDED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Duyệt một phiếu đã có quyết định -> TRIP_CLAIM_ALREADY_DECIDED")
    void approveClaim_throwsAlreadyDecided_whenClaimAlreadyDecided() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        // Ngoài đời, khi phiếu đã APPROVED thì chuyến đã được gán tài xế đó
        Trip tripAlreadyApproved = Trip.builder()
                .id(1L)
                .tripCode("TRIP-001")
                .company(company1)
                .status(TripStatus.PLANNED)
                .driver(driver1)
                .build();

        TripClaim alreadyApprovedClaim = TripClaim.builder()
                .id(10L)
                .trip(tripAlreadyApproved)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.APPROVED)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(alreadyApprovedClaim));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.approveClaim(1L, 10L));

        assertEquals(ErrorCode.TRIP_CLAIM_ALREADY_DECIDED, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("approveClaim với claimId thuộc chuyến khác -> TRIP_CLAIM_NOT_FOUND")
    void approveClaim_throwsNotFound_whenClaimBelongsToDifferentTrip() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim claimOfTrip2 = TripClaim.builder()
                .id(20L)
                .trip(openTrip2)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(20L, 1L)).thenReturn(Optional.of(claimOfTrip2));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.approveClaim(1L, 20L));

        assertEquals(ErrorCode.TRIP_CLAIM_NOT_FOUND, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejectClaim với claimId thuộc chuyến khác -> TRIP_CLAIM_NOT_FOUND")
    void rejectClaim_throwsNotFound_whenClaimBelongsToDifferentTrip() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim claimOfTrip2 = TripClaim.builder()
                .id(20L)
                .trip(openTrip2)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(20L, 1L)).thenReturn(Optional.of(claimOfTrip2));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.rejectClaim(1L, 20L, new RejectTripClaimRequest("Lý do từ chối")));

        assertEquals(ErrorCode.TRIP_CLAIM_NOT_FOUND, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    @Test
    @DisplayName("Từ chối một phiếu đã có quyết định -> TRIP_CLAIM_ALREADY_DECIDED")
    void rejectClaim_throwsAlreadyDecided_whenClaimAlreadyDecided() {
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim alreadyRejectedClaim = TripClaim.builder()
                .id(10L)
                .trip(openTrip1)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.REJECTED)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(alreadyRejectedClaim));

        AppException ex = assertThrows(AppException.class, () ->
                tripClaimService.rejectClaim(1L, 10L, new RejectTripClaimRequest("Không phù hợp")));

        assertEquals(ErrorCode.TRIP_CLAIM_ALREADY_DECIDED, ex.getErrorCode());
        verify(tripClaimRepository, never()).save(any());
    }

    @Test
    @DisplayName("Từ chối phiếu thành công với lý do")
    void rejectClaim_success() {
        when(currentUserService.getCurrentUser()).thenReturn(dispatcher);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);

        TripClaim pendingClaim = TripClaim.builder()
                .id(10L)
                .trip(openTrip1)
                .driver(driver1)
                .company(company1)
                .status(TripClaimStatus.PENDING)
                .build();

        when(tripClaimRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(pendingClaim));
        when(tripClaimRepository.save(any(TripClaim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TripClaimResponse response = tripClaimService.rejectClaim(1L, 10L, new RejectTripClaimRequest("Tài xế chưa đủ điều kiện"));

        assertEquals(TripClaimStatus.REJECTED, response.status());
        assertEquals("Tài xế chưa đủ điều kiện", response.rejectReason());
        assertEquals(dispatcher.getId(), response.decidedById());
        assertNotNull(response.decidedAt());
    }
}
