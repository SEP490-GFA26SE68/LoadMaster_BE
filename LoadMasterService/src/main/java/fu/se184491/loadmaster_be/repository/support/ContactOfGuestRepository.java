package fu.se184491.loadmaster_be.repository.support;

import fu.se184491.loadmaster_be.entity.support.ContactOfGuest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ContactOfGuestRepository extends JpaRepository<ContactOfGuest, Long>, JpaSpecificationExecutor<ContactOfGuest> {
}
