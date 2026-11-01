package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.dto.request.company.SignAgreementRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyAgreementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;

import java.time.LocalDateTime;

public interface CompanyAgreementService {

    CompanyAgreement createTrialAgreement(Company company, User adminUser, LocalDateTime approvedAt);

    CompanyAgreementResponse getMyAgreement(User currentUser);

    CompanyAgreementResponse signAgreement(User currentUser, SignAgreementRequest request, String clientIp);

    CompanyAgreementResponse getCompanyAgreement(Long companyId);
}
