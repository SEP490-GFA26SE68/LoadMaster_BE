package fu.se184491.loadmaster_be.service.company.Impl;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.CompanyRequest;
import fu.se184491.loadmaster_be.dto.request.company.UpdateCompanyProfileRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyOptionResponse;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService {

    private final CompanyRepository companyRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final UserRepository userRepository;
    private final CreditAccountRepository creditAccountRepository;

    @Override
    @Transactional
    public CompanyResponse createCompany(CompanyRequest request) {
        if (companyRepository.existsByCompanyCode(request.getCompanyCode())) {
            throw new AppException(ErrorCode.COMPANY_ALREADY_EXISTS);
        }
        if (companyRepository.existsByTaxCode(request.getTaxCode())) {
            throw new AppException(ErrorCode.TAX_CODE_ALREADY_EXISTS);
        }

        Company company = Company.builder()
                .companyCode(request.getCompanyCode())
                .companyName(request.getCompanyName())
                .taxCode(request.getTaxCode())
                .billingEmail(request.getBillingEmail())
                .status(request.getStatus() != null ? request.getStatus() : CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        Company saved = companyRepository.save(company);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public CompanyResponse updateCompany(Long id, CompanyRequest request) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        if (companyRepository.existsByTaxCodeAndIdNot(request.getTaxCode(), id)) {
            throw new AppException(ErrorCode.TAX_CODE_ALREADY_EXISTS);
        }

        company.setCompanyName(request.getCompanyName());
        company.setTaxCode(request.getTaxCode());
        company.setBillingEmail(request.getBillingEmail());
        if (request.getStatus() != null) {
            company.setStatus(request.getStatus());
        }

        Company updated = companyRepository.save(company);
        return toResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyResponse getCompanyById(Long id) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        return toResponse(company);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CompanyResponse> getAllCompanies(Pageable pageable) {
        return getAllCompanies(null, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CompanyResponse> getAllCompanies(String search, Pageable pageable) {
        if (search != null && !search.trim().isEmpty()) {
            String term = "%" + search.trim().toLowerCase() + "%";
            return companyRepository.findAll((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("companyName")), term),
                    cb.like(cb.lower(root.get("companyCode")), term),
                    cb.like(cb.lower(root.get("taxCode")), term),
                    cb.like(cb.lower(root.get("billingEmail")), term)
            ), pageable).map(this::toResponse);
        }
        return companyRepository.findAll(pageable).map(this::toResponse);
    }

    @Override
    @Transactional
    public CompanyResponse changeCompanyStatus(Long id, CompanyStatus status) {
        Company company = companyRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        company.setStatus(status);
        Company updated = companyRepository.save(company);
        return toResponse(updated);
    }

    @Transactional(readOnly = true)
    public List<CompanyOptionResponse> getCompanyOptions() {
        return companyRepository.findAll(
                        Sort.by(Sort.Direction.ASC, "companyName")
                ).stream()
                .map(company ->
                        new CompanyOptionResponse(
                                company.getId(),
                                company.getCompanyName()
                        )
                )
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyResponse getCompanyProfile(Long companyId) {
        if (companyId == null) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        return toResponse(company);
    }

    @Override
    @Transactional
    public CompanyResponse updateCompanyProfile(Long companyId, UpdateCompanyProfileRequest request) {
        if (companyId == null) {
            throw new AppException(ErrorCode.COMPANY_NOT_FOUND);
        }
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        if (request.getCompanyName() != null && !request.getCompanyName().isBlank()) {
            company.setCompanyName(request.getCompanyName());
        }
        if (request.getContactEmail() != null && !request.getContactEmail().isBlank()) {
            company.setBillingEmail(request.getContactEmail());
        }

        Company updated = companyRepository.save(company);
        return toResponse(updated);
    }

    private CompanyResponse toResponse(Company company) {
        CompanyResponse.CompanyResponseBuilder builder = CompanyResponse.builder()
                .id(company.getId())
                .companyCode(company.getCompanyCode())
                .companyName(company.getCompanyName())
                .taxCode(company.getTaxCode())
                .billingEmail(company.getBillingEmail())
                .status(company.getStatus())
                .createdAt(company.getCreatedAt());

        if (companySubscriptionRepository != null && company.getId() != null) {
            companySubscriptionRepository.findByCompanyId(company.getId()).ifPresent(sub -> {
                if (sub.getPlan() != null) {
                    builder.planName(sub.getPlan().getName());
                    builder.planCode(sub.getPlan().getPlanCode());
                    builder.planTier(sub.getPlan().getTier() != null ? sub.getPlan().getTier().name() : null);
                }
                if (sub.getStatus() != null) {
                    builder.subscriptionStatus(sub.getStatus().name());
                }
                builder.subscriptionStartedAt(sub.getStartedAt());
                builder.subscriptionExpiresAt(sub.getExpiresAt());
            });
        }

        if (creditAccountRepository != null && company.getId() != null) {
            creditAccountRepository.findByCompanyId(company.getId()).ifPresent(credit -> {
                builder.creditBalance(credit.getBalance());
            });
        }

        if (userRepository != null && company.getId() != null) {
            List<User> users = userRepository.findByCompanyId(company.getId());
            if (users != null) {
                builder.userCount(users.size());
                users.stream()
                        .filter(u -> u.getUserRoleType() == UserRole.ADMIN)
                        .findFirst()
                        .or(() -> users.stream().findFirst())
                        .ifPresent(admin -> {
                            builder.adminId(admin.getId());
                            builder.adminName(admin.getFullName());
                            builder.adminEmail(admin.getEmail());
                            builder.adminPhone(admin.getPhoneNumber());
                        });
            } else {
                builder.userCount(0);
            }
        }

        return builder.build();
    }
}
