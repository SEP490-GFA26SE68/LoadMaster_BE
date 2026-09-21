package fu.se184491.loadmaster_be.repository;

import fu.se184491.loadmaster_be.constant.CompanyStatus;
import fu.se184491.loadmaster_be.entity.Company;
import fu.se184491.loadmaster_be.entity.VehicleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class VehicleTypeRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private VehicleTypeRepository repository;

    private Company createTestCompany() {
        Company company = Company.builder()
                .companyCode("C001")
                .companyName("Test Company")
                .taxCode("123456789")
                .billingEmail("test@company.com")
                .status(CompanyStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        return entityManager.persist(company);
    }

    private VehicleType createTestVehicleType(Company company, String name) {
        VehicleType type = VehicleType.builder()
                .company(company)
                .name(name)
                .innerLength(4200)
                .innerWidth(2000)
                .innerHeight(1800)
                .maxPayloadKg(BigDecimal.valueOf(5000.00))
                .doorWidth(1900)
                .doorHeight(1700)
                .build();
        return entityManager.persist(type);
    }

    @Test
    void shouldFindVehicleTypeById() {
        Company company = createTestCompany();
        VehicleType type = createTestVehicleType(company, "Xe tải 5 tấn");
        entityManager.flush();
        entityManager.clear();

        Optional<VehicleType> found = repository.findById(type.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Xe tải 5 tấn");
    }

    @Test
    void shouldFindByCompanyIdWithPagination() {
        Company company = createTestCompany();
        Company otherCompany = Company.builder()
                .companyCode("C002")
                .companyName("Other Company")
                .taxCode("987654321")
                .billingEmail("other@company.com")
                .status(CompanyStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
        otherCompany = entityManager.persist(otherCompany);

        createTestVehicleType(company, "Type 1");
        createTestVehicleType(company, "Type 2");
        createTestVehicleType(company, "Type 3");
        createTestVehicleType(otherCompany, "Other Type 1");
        
        entityManager.flush();
        entityManager.clear();

        Page<VehicleType> page = repository.findByCompanyId(company.getId(), PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getName()).isEqualTo("Type 1");
    }

    @Test
    void shouldDeleteVehicleType() {
        Company company = createTestCompany();
        VehicleType type = createTestVehicleType(company, "Type to delete");
        entityManager.flush();
        entityManager.clear();

        repository.deleteById(type.getId());
        
        Optional<VehicleType> found = repository.findById(type.getId());
        assertThat(found).isEmpty();
    }
}
