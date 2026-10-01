package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.service.cargo.PackageTypeService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PackageTypeServiceImpl implements PackageTypeService {

    private final PackageTypeRepository packageTypeRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public PackageTypeResponse createPackageType(Long companyId, PackageTypeRequest request) {
        Company company = entityManager.getReference(Company.class, companyId);

        PackageType packageType = PackageType.builder()
                .company(company)
                .name(request.getName())
                .length(request.getLength())
                .width(request.getWidth())
                .height(request.getHeight())
                .weightKg(request.getWeightKg())
                .maxStackingWeightKg(request.getMaxStackingWeightKg())
                .isFragile(request.getIsFragile() != null ? request.getIsFragile() : false)
                .build();

        PackageType saved = packageTypeRepository.save(packageType);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public PackageTypeResponse updatePackageType(Long companyId, Long id, PackageTypeRequest request) {
        PackageType packageType = packageTypeRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        packageType.setName(request.getName());
        packageType.setLength(request.getLength());
        packageType.setWidth(request.getWidth());
        packageType.setHeight(request.getHeight());
        packageType.setWeightKg(request.getWeightKg());
        packageType.setMaxStackingWeightKg(request.getMaxStackingWeightKg());
        if (request.getIsFragile() != null) {
            packageType.setIsFragile(request.getIsFragile());
        }

        PackageType updated = packageTypeRepository.save(packageType);
        return toResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public PackageTypeResponse getPackageTypeById(Long companyId, Long id) {
        PackageType packageType = packageTypeRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));
        return toResponse(packageType);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PackageTypeResponse> getAllPackageTypes(Long companyId, Pageable pageable) {
        return packageTypeRepository.findByCompanyId(companyId, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PackageTypeResponse> getAllPackageTypes(Long companyId) {
        return packageTypeRepository.findByCompanyId(companyId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deletePackageType(Long companyId, Long id) {
        PackageType packageType = packageTypeRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));
        packageTypeRepository.delete(packageType);
    }

    private PackageTypeResponse toResponse(PackageType entity) {
        return PackageTypeResponse.builder()
                .id(entity.getId())
                .companyId(entity.getCompany() != null ? entity.getCompany().getId() : null)
                .name(entity.getName())
                .length(entity.getLength())
                .width(entity.getWidth())
                .height(entity.getHeight())
                .weightKg(entity.getWeightKg())
                .maxStackingWeightKg(entity.getMaxStackingWeightKg())
                .isFragile(entity.getIsFragile())
                .build();
    }
}
