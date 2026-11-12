package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.audit.AuditLogQueryService;
import fu.se184491.loadmaster_be.service.export.PdfLabelExportService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageTraceServiceTest {

    @Mock PackageRepository packageRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock AuditLogQueryService auditLogQueryService;
    @Mock TripLookupHelper tripLookupHelper;
    @Mock TripPackageResolver tripPackageResolver;
    @Mock PdfLabelExportService pdfLabelExportService;

    @InjectMocks PackageTraceService service;

    @Test
    void historyReadsTheCompanysLogAboutThePackage() {
        CargoPackage pkg = CargoPackage.builder().id(7L).build();
        AuditLog found = AuditLog.builder().id(1L).actionType("PACKAGE_FOUND").build();
        AuditLogResponse row = AuditLogResponse.builder().id(1L).actionType("PACKAGE_FOUND").build();
        when(packageRepository.findByIdAndCompany_Id(7L, 10L)).thenReturn(Optional.of(pkg));
        when(auditLogRepository.findPackageHistory(10L, "7", PackageTraceService.HISTORY_LIMIT)).thenReturn(List.of(found));
        when(auditLogQueryService.responsesOf(List.of(found))).thenReturn(List.of(row));

        assertThat(service.history(10L, 7L)).containsExactly(row);
    }

    @Test
    void historyOfAnotherCompanysPackageIsNotFound() {
        when(packageRepository.findByIdAndCompany_Id(7L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(10L, 7L))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_NOT_FOUND);
        verify(auditLogRepository, never()).findPackageHistory(anyLong(), anyString(), anyInt());
    }

    @Test
    void tripLabelsPrintEveryPackageTheTripHolds() {
        Trip trip = Trip.builder().id(5L).tripCode("TRIP-5").build();
        when(tripLookupHelper.requireTrip("TRIP-5", 10L)).thenReturn(trip);
        when(tripPackageResolver.packagesOfTrip(5L)).thenReturn(List.of(
                CargoPackage.builder().id(3L).build(), CargoPackage.builder().id(4L).build()));
        when(pdfLabelExportService.generateLabelsPdf(List.of(3L, 4L), 10L)).thenReturn(new byte[] {1, 2});

        assertThat(service.tripLabelsPdf(10L, "TRIP-5")).containsExactly(1, 2);
    }

    @Test
    void aTripWithoutPackagesHasNoLabels() {
        when(tripLookupHelper.requireTrip("5", 10L)).thenReturn(Trip.builder().id(5L).build());
        when(tripPackageResolver.packagesOfTrip(5L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.tripLabelsPdf(10L, "5"))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.TRIP_HAS_NO_PACKAGES);
        verify(pdfLabelExportService, never()).generateLabelsPdf(any(), any());
    }
}
