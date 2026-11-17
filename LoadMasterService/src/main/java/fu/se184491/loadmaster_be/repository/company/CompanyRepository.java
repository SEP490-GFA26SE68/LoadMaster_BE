package fu.se184491.loadmaster_be.repository.company;

import fu.se184491.loadmaster_be.entity.company.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {
    // A stable parent row also serializes first-time credit-account creation.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Company c where c.id = :id")
    java.util.Optional<Company> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByCompanyCode(String companyCode);
    boolean existsByTaxCode(String taxCode);
    boolean existsByTaxCodeAndIdNot(String taxCode, Long id);
    java.util.Optional<Company> findByCompanyCode(String companyCode);
}
