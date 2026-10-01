package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.CompanyRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CompanyService {
    CompanyResponse createCompany(CompanyRequest request);
    CompanyResponse updateCompany(Long id, CompanyRequest request);
    CompanyResponse getCompanyById(Long id);
    Page<CompanyResponse> getAllCompanies(Pageable pageable);
    CompanyResponse changeCompanyStatus(Long id, CompanyStatus status);
}
