package fu.se184491.loadmaster_be.service.company.Impl;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.CompanyRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CompanyServiceImpl implements CompanyService {

    private final CompanyRepository companyRepository;

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

    private CompanyResponse toResponse(Company company) {
        return CompanyResponse.builder()
                .id(company.getId())
                .companyCode(company.getCompanyCode())
                .companyName(company.getCompanyName())
                .taxCode(company.getTaxCode())
                .billingEmail(company.getBillingEmail())
                .status(company.getStatus())
                .createdAt(company.getCreatedAt())
                .build();
    }
}
