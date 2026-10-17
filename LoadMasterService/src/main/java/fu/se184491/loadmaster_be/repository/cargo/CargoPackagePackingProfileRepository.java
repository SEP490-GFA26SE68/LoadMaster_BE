
package fu.se184491.loadmaster_be.repository.cargo;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CargoPackagePackingProfileRepository
        extends JpaRepository<CargoPackagePackingProfile, Long> {

    // Tìm thông số xếp hàng theo ID kiện.
    Optional<CargoPackagePackingProfile> findByCargoPackageId(
            Long packageId
    );

    // Kiểm tra kiện đã có packing profile hay chưa.
    boolean existsByCargoPackageId(Long packageId);

    // Xóa packing profile trước khi xóa kiện vật lý.
    void deleteByCargoPackageId(Long packageId);
}
