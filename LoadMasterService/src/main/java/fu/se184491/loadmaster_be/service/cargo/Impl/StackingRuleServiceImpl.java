package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.dto.request.cargo.StackingRuleRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.StackingRuleResponse;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.StackingRule;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.PackageTypeRepository;
import fu.se184491.loadmaster_be.repository.cargo.StackingRuleRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.cargo.StackingRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class StackingRuleServiceImpl implements StackingRuleService {

    private final StackingRuleRepository stackingRuleRepository;
    private final PackageTypeRepository packageTypeRepository;
    private final CompanyRepository companyRepository;

    private static final BigDecimal DEFAULT_MIN_CONTACT_RATIO = new BigDecimal("0.80");

    private StackingRuleResponse mapToResponse(StackingRule rule) {
        return StackingRuleResponse.builder()
                .id(rule.getId())
                .companyId(rule.getCompany() != null ? rule.getCompany().getId() : null)
                .bottomPackageTypeId(rule.getBottomType() != null ? rule.getBottomType().getId() : null)
                .bottomPackageTypeName(rule.getBottomType() != null ? rule.getBottomType().getName() : null)
                .topPackageTypeId(rule.getTopType() != null ? rule.getTopType().getId() : null)
                .topPackageTypeName(rule.getTopType() != null ? rule.getTopType().getName() : null)
                .isAllowed(rule.getIsAllowed())
                .minContactRatio(rule.getMinContactRatio())
                .build();
    }

    @Override
    @Transactional
    public StackingRuleResponse createStackingRule(Long companyId, StackingRuleRequest request) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        PackageType bottomType = packageTypeRepository.findById(request.getBottomPackageTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        if (!bottomType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        PackageType topType = packageTypeRepository.findById(request.getTopPackageTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        if (!topType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (stackingRuleRepository.existsByCompanyIdAndBottomTypeIdAndTopTypeId(companyId, bottomType.getId(), topType.getId())) {
            throw new AppException(ErrorCode.STACKING_RULE_ALREADY_EXISTS);
        }

        StackingRule rule = StackingRule.builder()
                .company(company)
                .bottomType(bottomType)
                .topType(topType)
                .isAllowed(request.getIsAllowed() != null ? request.getIsAllowed() : true)
                .minContactRatio(request.getMinContactRatio() != null ? request.getMinContactRatio() : DEFAULT_MIN_CONTACT_RATIO)
                .build();

        rule = stackingRuleRepository.save(rule);
        return mapToResponse(rule);
    }

    @Override
    @Transactional
    public StackingRuleResponse updateStackingRule(Long companyId, Long id, StackingRuleRequest request) {
        StackingRule rule = stackingRuleRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.STACKING_RULE_NOT_FOUND));

        if (!rule.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        PackageType bottomType = packageTypeRepository.findById(request.getBottomPackageTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        if (!bottomType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        PackageType topType = packageTypeRepository.findById(request.getTopPackageTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.PACKAGE_TYPE_NOT_FOUND));

        if (!topType.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (stackingRuleRepository.existsByCompanyIdAndBottomTypeIdAndTopTypeIdAndIdNot(companyId, bottomType.getId(), topType.getId(), id)) {
            throw new AppException(ErrorCode.STACKING_RULE_ALREADY_EXISTS);
        }

        rule.setBottomType(bottomType);
        rule.setTopType(topType);
        if (request.getIsAllowed() != null) {
            rule.setIsAllowed(request.getIsAllowed());
        }
        if (request.getMinContactRatio() != null) {
            rule.setMinContactRatio(request.getMinContactRatio());
        }

        rule = stackingRuleRepository.save(rule);
        return mapToResponse(rule);
    }

    @Override
    @Transactional
    public void deleteStackingRule(Long companyId, Long id) {
        StackingRule rule = stackingRuleRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.STACKING_RULE_NOT_FOUND));

        if (!rule.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        stackingRuleRepository.delete(rule);
    }

    @Override
    public StackingRuleResponse getStackingRuleById(Long companyId, Long id) {
        StackingRule rule = stackingRuleRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.STACKING_RULE_NOT_FOUND));

        if (!rule.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return mapToResponse(rule);
    }

    @Override
    public Page<StackingRuleResponse> getAllStackingRules(Long companyId, Pageable pageable) {
        return stackingRuleRepository.findByCompanyId(companyId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    public Boolean isStackingAllowed(Long companyId, Long bottomPackageTypeId, Long topPackageTypeId) {
        return stackingRuleRepository.findByCompanyIdAndBottomTypeIdAndTopTypeId(companyId, bottomPackageTypeId, topPackageTypeId)
                .map(StackingRule::getIsAllowed)
                .orElse(true);
    }
}
