package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import jakarta.persistence.criteria.Predicate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Kho kiện của công ty, như màn {@code /kien-hang}: cùng phạm vi với {@code GET /api/packages}
 * ({@code PACKAGE_MANAGE}, {@code ROLE_DISPATCHER}, {@code ROLE_MANAGER} — nhân viên kho, điều phối viên, quản lý công
 * ty), luôn lọc theo công ty của người đang hỏi; thêm lọc theo cờ như bộ lọc "Cờ" của màn. Mới nhất trước. Chỉ đọc. Không
 * trả mã số nội bộ, mã QR hay mã vạch.
 */
@Component
public class PackagePoolTool implements AssistantTool {

    private final CurrentUserService currentUser;
    private final CargoPackageRepository packages;
    private final TransactionOperations transactions;

    @Autowired
    public PackagePoolTool(CurrentUserService currentUser, CargoPackageRepository packages,
                           PlatformTransactionManager transactionManager) {
        this(currentUser, packages, readOnly(transactionManager));
    }

    PackagePoolTool(CurrentUserService currentUser, CargoPackageRepository packages,
                    TransactionOperations transactions) {
        this.currentUser = currentUser;
        this.packages = packages;
        this.transactions = transactions;
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /** Một kiện của kho kiện; chuyến và điểm giao chỉ khi kiện đã vào chuyến. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolPackage(String packageCode, String packageName, PackageStatus status, PackageFlag flag,
                              HandlingClass handlingClass, BigDecimal weightKg, String destination, String tripCode,
                              Integer stopNumber) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.WAREHOUSE_WORKER, UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Tool(name = "list_packages", description = """
            Kiện trong kho kiện của công ty như màn Kho kiện, mới nhất trước, tối đa 30 dòng kèm total là tổng số \
            khớp bộ lọc. Lọc theo trạng thái (PENDING đã nhập, chưa vào chuyến; PLANNED đã gán chuyến; LOADED đã \
            xếp lên xe; DELIVERED đã giao; RETURNED hoàn trả; CANCELLED đã huỷ) và theo cờ (NOT_FOUND kho báo không \
            tìm thấy, DAMAGED hư hỏng, NONE không cờ). Mỗi dòng: mã kiện, tên, trạng thái, cờ, loại hàng, khối lượng \
            (kg), điểm đến, chuyến và số điểm giao nếu đã vào chuyến. Bộ lọc sai giá trị trả lỗi INVALID_FILTER. \
            Dùng khi hỏi kho còn bao nhiêu kiện chưa vào chuyến, kiện nào đang mang cờ; hỏi một kiện theo mã thì \
            dùng công cụ tra kiện.""")
    public Object listPackages(
            @ToolParam(required = false, description = "Trạng thái kiện: PENDING, PLANNED, LOADED, DELIVERED, RETURNED hoặc CANCELLED")
            String status,
            @ToolParam(required = false, description = "Cờ: NOT_FOUND, DAMAGED, hoặc NONE là kiện không mang cờ")
            String flag) {
        PackageStatus statusFilter;
        PackageFlag flagFilter;
        boolean noFlag;
        try {
            String flagCode = code(flag);
            noFlag = "NONE".equals(flagCode);
            statusFilter = code(status) == null ? null : PackageStatus.valueOf(code(status));
            flagFilter = flagCode == null || noFlag ? null : PackageFlag.valueOf(flagCode);
        } catch (IllegalArgumentException e) {
            return ToolResults.error(ToolResults.INVALID_FILTER);
        }
        Long companyId = currentUser.getCurrentCompanyId();
        Specification<CargoPackage> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            if (flagFilter != null) {
                predicates.add(cb.equal(root.get("flag"), flagFilter));
            } else if (noFlag) {
                predicates.add(cb.isNull(root.get("flag")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return Objects.requireNonNull(transactions.execute(tx -> {
            Page<CargoPackage> page = packages.findAll(spec,
                    PageRequest.of(0, ToolResults.LIST_ROWS, Sort.by(Sort.Direction.DESC, "id")));
            List<PoolPackage> rows = page.getContent().stream().map(PackagePoolTool::rowOf).toList();
            return ToolResults.list(rows, page.getTotalElements());
        }));
    }

    private static PoolPackage rowOf(CargoPackage pkg) {
        DeliveryStop stop = pkg.getOrder() != null ? pkg.getOrder().getDeliveryStop() : null;
        return new PoolPackage(pkg.getPackageCode(), pkg.getPackageName(), pkg.getStatus(), pkg.getFlag(),
                pkg.getHandlingClass() != null ? pkg.getHandlingClass() : HandlingClass.STANDARD,
                pkg.getActualWeightKg(), pkg.getDestination(),
                stop != null && stop.getTrip() != null ? stop.getTrip().getTripCode() : null,
                stop != null ? stop.getStopSequence() : null);
    }

    private static String code(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
