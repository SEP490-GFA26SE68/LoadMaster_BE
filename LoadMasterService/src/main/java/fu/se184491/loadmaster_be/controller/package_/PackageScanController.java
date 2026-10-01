package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.dto.response.PackageDetailResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackageScanController {

    private final QrCodeService qrCodeService;

    /**
     * Scan a package by QR token.
     * Accessible by DISPATCHER and WAREHOUSE_WORKER.
     *
     * @param qrToken the UUID token encoded in the QR image
     * @return package detail response
     */
    @GetMapping("/scan/{qrToken}")
    @PreAuthorize("hasAnyAuthority('DISPATCHER', 'WAREHOUSE_WORKER')")
    public ResponseEntity<PackageDetailResponse> scanPackage(@PathVariable String qrToken) {
        CargoPackage pkg = qrCodeService.lookupByToken(qrToken);

        PackageDetailResponse response = PackageDetailResponse.builder()
                .id(pkg.getId())
                .packageCode(pkg.getPackageCode())
                .qrToken(pkg.getQrToken())
                .handlingClass(pkg.getHandlingClass() != null ? pkg.getHandlingClass() : HandlingClass.STANDARD)
                .actualWeightKg(pkg.getActualWeightKg())
                .status(pkg.getStatus())
                .orderId(pkg.getOrder() != null ? pkg.getOrder().getId() : null)
                .build();

        return ResponseEntity.ok(response);
    }


}
