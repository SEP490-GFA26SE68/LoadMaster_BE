package fu.se184491.loadmaster_be.repository.company;

import fu.se184491.loadmaster_be.entity.company.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    boolean existsByCompanyCode(String companyCode);
    boolean existsByTaxCode(String taxCode);
    boolean existsByTaxCodeAndIdNot(String taxCode, Long id);
    java.util.Optional<Company> findByCompanyCode(String companyCode);
}
