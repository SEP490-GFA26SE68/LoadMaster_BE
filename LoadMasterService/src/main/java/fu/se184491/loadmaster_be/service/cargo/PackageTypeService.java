package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PackageTypeService {
    PackageTypeResponse createPackageType(Long companyId, PackageTypeRequest request);
    PackageTypeResponse updatePackageType(Long companyId, Long id, PackageTypeRequest request);
    PackageTypeResponse getPackageTypeById(Long companyId, Long id);
    Page<PackageTypeResponse> getAllPackageTypes(Long companyId, Pageable pageable);
    List<PackageTypeResponse> getAllPackageTypes(Long companyId);
    void deletePackageType(Long companyId, Long id);
}
