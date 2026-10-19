
package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.dto.request.packagetype.PackageTypeRequest;
import fu.se184491.loadmaster_be.dto.response.packagetype.PackageTypeResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.service.cargo.PackageTypeService;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PackageTypeServiceImpl implements PackageTypeService {

    private final PackageTypeRepository packageTypeRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public PackageTypeResponse createPackageType(
            Long companyId,
            PackageTypeRequest request
    ) {
        User currentUser = currentUserService.getCurrentUser();

        Long currentCompanyId = getCurrentCompanyId();
        String typeCode = normalizeTypeCode(request.getTypeCode());

        if (typeCode != null &&
                packageTypeRepository.existsByCompanyIdAndTypeCodeIgnoreCase(
                        currentCompanyId, typeCode)) {
            throw new AppException(ErrorCode.PACKAGE_TYPE_CODE_ALREADY_EXISTS);
        }

        PackageType packageType = PackageType.builder()
                .company(currentUser.getCompany())
                .typeCode(typeCode)
                .name(request.getName().trim())
                .length(request.getLength())
                .width(request.getWidth())
                .height(request.getHeight())
                .weightKg(request.getWeightKg())
                .maxStackingWeightKg(request.getMaxStackingWeightKg())
                .isFragile(defaultFalse(request.getIsFragile()))
                .rotationAllowed(defaultTrue(request.getRotationAllowed()))
                .allowRotateX(defaultTrue(request.getAllowRotateX()))
                .allowRotateY(defaultTrue(request.getAllowRotateY()))
                .allowRotateZ(defaultTrue(request.getAllowRotateZ()))
                .build();

        PackageType saved = packageTypeRepository.save(packageType);

        return toResponse(saved);
    }

    @Override
    @Transactional
    public PackageTypeResponse updatePackageType(
            Long companyId,
            Long id,
            PackageTypeRequest request
    ) {
        Long currentCompanyId = getCurrentCompanyId();

        PackageType packageType = packageTypeRepository
                .findByIdAndCompanyId(id, currentCompanyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND)
                );

        String typeCode = normalizeTypeCode(request.getTypeCode());

        if (typeCode != null &&
                packageTypeRepository.existsByCompanyIdAndTypeCodeIgnoreCaseAndIdNot(
                        currentCompanyId, typeCode, id)) {
            throw new AppException(ErrorCode.PACKAGE_TYPE_CODE_ALREADY_EXISTS);
        }

        packageType.setTypeCode(typeCode);

        packageType.setTypeCode(request.getTypeCode());
        packageType.setName(request.getName().trim());
        packageType.setLength(request.getLength());
        packageType.setWidth(request.getWidth());
        packageType.setHeight(request.getHeight());
        packageType.setWeightKg(request.getWeightKg());
        packageType.setMaxStackWeightKg(request.getMaxStackingWeightKg());

        packageType.setFragile(defaultFalse(request.getIsFragile()));
        packageType.setRotationAllowed(defaultTrue(request.getRotationAllowed()));
        packageType.setAllowRotateX(defaultTrue(request.getAllowRotateX()));
        packageType.setAllowRotateY(defaultTrue(request.getAllowRotateY()));
        packageType.setAllowRotateZ(defaultTrue(request.getAllowRotateZ()));

        return toResponse(packageType);
    }

    @Override
    @Transactional(readOnly = true)
    public PackageTypeResponse getPackageTypeById(
            Long companyId,
            Long id
    ) {
        Long currentCompanyId = getCurrentCompanyId();

        PackageType packageType = packageTypeRepository
                .findByIdAndCompanyId(id, currentCompanyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND)
                );

        return toResponse(packageType);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PackageTypeResponse> getAllPackageTypes(
            Long companyId,
            Pageable pageable
    ) {
        Long currentCompanyId = getCurrentCompanyId();

        return packageTypeRepository
                .findByCompanyId(currentCompanyId, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PackageTypeResponse> getAllPackageTypes(Long companyId) {
        Long currentCompanyId = getCurrentCompanyId();

        return packageTypeRepository
                .findByCompanyId(currentCompanyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deletePackageType(Long companyId, Long id) {
        Long currentCompanyId = getCurrentCompanyId();

        PackageType packageType = packageTypeRepository
                .findByIdAndCompanyId(id, currentCompanyId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND)
                );

        packageTypeRepository.delete(packageType);
    }

    private PackageTypeResponse toResponse(PackageType entity) {
        return PackageTypeResponse.builder()
                .id(entity.getId())
                .companyId(
                        entity.getCompany() != null
                                ? entity.getCompany().getId()
                                : null
                )
                .typeCode(entity.getTypeCode())
                .name(entity.getName())
                .length(entity.getLength())
                .width(entity.getWidth())
                .height(entity.getHeight())
                .weightKg(entity.getWeightKg())
                .maxStackingWeightKg(entity.getMaxStackWeightKg())
                .isFragile(entity.getFragile())
                .rotationAllowed(entity.getRotationAllowed())
                .allowRotateX(entity.getAllowRotateX())
                .allowRotateY(entity.getAllowRotateY())
                .allowRotateZ(entity.getAllowRotateZ())
                .build();
    }

    private Long getCurrentCompanyId() {
        return getCurrentCompanyId(currentUserService.getCurrentUser());
    }

    private Long getCurrentCompanyId(User currentUser) {
        if (currentUser.getCompany() == null) {
            throw new AppException(ErrorCode.COMPANY_REQUIRED);
        }

        return currentUser.getCompany().getId();
    }

    private boolean defaultTrue(Boolean value) {
        return value == null || value;
    }

    private boolean defaultFalse(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private String normalizeTypeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
