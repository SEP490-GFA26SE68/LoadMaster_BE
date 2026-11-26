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

    default CompanyAgreementResponse signAgreement(User currentUser, SignAgreementRequest request, String clientIp) {
        return signAgreement(currentUser, request, clientIp, null);
    }

    CompanyAgreementResponse signAgreement(User currentUser, SignAgreementRequest request, String clientIp, String signerDevice);

    CompanyAgreementResponse getCompanyAgreement(Long companyId);

}
