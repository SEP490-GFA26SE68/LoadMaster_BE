package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Profile("dev")
@Order(1)
@RequiredArgsConstructor
public class DevCompanyInitializer implements ApplicationRunner {

    private final CompanyRepository companyRepository;

    @Override
    public void run(ApplicationArguments args) {

        createIfNotExists(
                "LM-LOGISTICS",
                "LoadMaster Logistics",
                "TAX-LM-001",
                "billing@loadmaster.local"
        );

        createIfNotExists(
                "FAST-MOVE",
                "FastMove Transport",
                "TAX-FM-001",
                "billing@fastmove.local"
        );

        createIfNotExists(
                "TRANS-GLOBAL",
                "TransGlobal Freight",
                "TAX-TG-001",
                "billing@transglobal.local"
        );
    }

    private void createIfNotExists(
            String companyCode,
            String companyName,
            String taxCode,
            String billingEmail
    ) {
        if (companyRepository.existsByCompanyCode(companyCode)) {
            return;
        }

        Company company = Company.builder()
                .companyCode(companyCode)
                .companyName(companyName)
                .taxCode(taxCode)
                .billingEmail(billingEmail)
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        companyRepository.save(company);
    }
}