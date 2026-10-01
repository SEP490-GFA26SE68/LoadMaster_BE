package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackageTypeRepository extends JpaRepository<PackageType, Long> {
    List<PackageType> findByCompanyId(Long companyId);
    Page<PackageType> findByCompanyId(Long companyId, Pageable pageable);
    Optional<PackageType> findByIdAndCompanyId(Long id, Long companyId);
}
