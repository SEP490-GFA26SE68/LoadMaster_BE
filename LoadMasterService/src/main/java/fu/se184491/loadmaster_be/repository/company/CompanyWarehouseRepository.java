package fu.se184491.loadmaster_be.repository.company;

import fu.se184491.loadmaster_be.entity.company.CompanyWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompanyWarehouseRepository
        extends JpaRepository<CompanyWarehouse, Long> {

    // Danh sách kho của công ty, kho mặc định đứng đầu.
    List<CompanyWarehouse> findByCompanyIdOrderByIsDefaultDescWarehouseNameAsc(
            Long companyId
    );

    // Lấy kho theo ID và công ty, tránh truy cập chéo tenant.
    Optional<CompanyWarehouse> findByIdAndCompanyId(
            Long id,
            Long companyId
    );

    // Lấy kho đang hoạt động để Dispatcher chọn khi tạo Trip.
    List<CompanyWarehouse> findByCompanyIdAndIsActiveTrueOrderByWarehouseNameAsc(
            Long companyId
    );

    // Tìm kho mặc định.
    Optional<CompanyWarehouse> findByCompanyIdAndIsDefaultTrue(
            Long companyId
    );

    // Kiểm tra công ty đã có kho mặc định chưa.
    boolean existsByCompanyIdAndIsDefaultTrue(
            Long companyId
    );

    // Bỏ trạng thái mặc định của các kho cũ.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE CompanyWarehouse w
        SET w.isDefault = false
        WHERE w.company.id = :companyId
          AND w.isDefault = true
    """)
    int clearDefaultWarehouse(@Param("companyId") Long companyId);
}
