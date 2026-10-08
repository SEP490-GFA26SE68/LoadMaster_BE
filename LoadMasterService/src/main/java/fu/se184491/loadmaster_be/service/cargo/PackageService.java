package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.request.cargo.PackageRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PackageService {

    PackageResponse createPackage(Long companyId, PackageRequest request);

    PackageResponse updatePackage(Long companyId, Long id, PackageRequest request);

    PackageResponse getPackageById(Long companyId, Long id);

    void deletePackage(Long companyId, Long id);

    Page<PackageResponse> getAllPackages(Long companyId, Long orderId, Long packageTypeId, Pageable pageable);
}
