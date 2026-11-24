package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.dto.response.cargo.PackageResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.cargo.PackageService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Tra cứu kiện theo mã, như màn Tra cứu kiện: mã QR ({@link QrCodeService#lookupByToken}, như
 * {@code GET /api/packages/scan/{qrToken}}), mã kiện khớp đúng cả mã, hoặc mã vạch của bên gửi. Mọi lối đọc đều lọc
 * theo công ty của người đang hỏi: kiện của công ty khác coi như không có. Chuyến và điểm giao suy như màn quét
 * (kiện → đơn → điểm giao → chuyến). Không trả mã số nội bộ hay mã QR.
 */
@Component
public class PackageLookupTool implements AssistantTool {

    /** Số kiện tối đa trả về cho một mã (mã của bên gửi có thể trùng nhiều kiện). */
    static final int MAX_MATCHES = 5;
    /** Số kiện đọc khi tìm theo mã vạch, trước khi lọc khớp đúng. */
    private static final int BARCODE_WINDOW = 50;
    private static final int MAX_CODE_LENGTH = 100;

    private final CurrentUserService currentUser;
    private final QrCodeService qrCodes;
    private final PackageService packages;
    private final TransactionOperations transactions;

    @Autowired
    public PackageLookupTool(CurrentUserService currentUser, QrCodeService qrCodes, PackageService packages,
                             PlatformTransactionManager transactionManager) {
        this(currentUser, qrCodes, packages, readOnly(transactionManager));
    }

    PackageLookupTool(CurrentUserService currentUser, QrCodeService qrCodes, PackageService packages,
                      TransactionOperations transactions) {
        this.currentUser = currentUser;
        this.qrCodes = qrCodes;
        this.packages = packages;
        this.transactions = transactions;
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /** {@code total} là số kiện khớp; {@code packages} tối đa {@value #MAX_MATCHES} kiện. */
    public record Lookup(String code, int total, List<Match> packages) {
    }

    /**
     * Một kiện khớp mã. {@code status}: PENDING chưa xếp, PLANNED đã vào phương án, LOADED đã lên xe, DELIVERED đã
     * giao, RETURNED hoàn trả, CANCELLED đã huỷ. {@code flag}: NOT_FOUND kho báo không tìm thấy, DAMAGED hỏng.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Match(String packageCode, String senderBarcode, String packageName, PackageStatus status,
                        PackageFlag flag, HandlingClass handlingClass, BigDecimal weightKg, String destination,
                        String tripCode, Integer stopNumber, String stopName) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.WAREHOUSE_WORKER, UserRole.DISPATCHER);
    }

    @Tool(name = "lookup_package", description = """
            Tra cứu kiện của công ty theo mã người dùng đưa: mã QR in trên nhãn, mã kiện (khớp đúng cả mã) hoặc mã \
            vạch của bên gửi. Trả trạng thái kiện (PENDING chưa xếp, PLANNED đã vào phương án, LOADED đã lên xe, \
            DELIVERED đã giao, RETURNED hoàn trả, CANCELLED đã huỷ), cờ (NOT_FOUND kho báo không tìm thấy, DAMAGED \
            hỏng), loại hàng, khối lượng (kg), điểm đến, chuyến và điểm giao của kiện nếu kiện đã vào chuyến. total \
            = 0 nghĩa là công ty không có kiện mang mã đó: nói không tìm thấy, không đoán. Dùng khi hỏi một kiện đang \
            ở đâu, thuộc chuyến nào, đã xếp hay giao chưa.""")
    public Lookup lookupPackage(
            @ToolParam(description = "Mã người dùng đưa, giữ nguyên như họ gõ (mã QR, mã kiện hoặc mã vạch)")
            String code) {
        String raw = code == null ? "" : code.strip();
        if (raw.isEmpty() || raw.length() > MAX_CODE_LENGTH) {
            return new Lookup(raw, 0, List.of());
        }
        Long companyId = currentUser.getCurrentCompanyId();
        List<Long> ids = matchingIds(raw, companyId);
        List<Match> rows = ids.isEmpty() ? List.of() : Objects.requireNonNull(transactions.execute(status ->
                ids.stream().limit(MAX_MATCHES).map(id -> matchOf(id, companyId)).filter(Objects::nonNull).toList()));
        return new Lookup(raw, ids.size(), rows);
    }

    private List<Long> matchingIds(String raw, Long companyId) {
        Set<Long> ids = new LinkedHashSet<>();
        // Mã QR phân biệt hoa thường và web luôn gửi chữ thường: thử nguyên văn rồi chữ thường.
        for (String token : List.of(raw, raw.toLowerCase(Locale.ROOT))) {
            try {
                ids.add(qrCodes.lookupByToken(token, companyId).getId());
            } catch (AppException notFound) {
                // không phải mã QR của công ty
            }
        }
        packages.searchPackages(companyId, null, null, raw, null, null, null, PageRequest.of(0, MAX_MATCHES))
                .forEach(pkg -> ids.add(pkg.getId()));
        packages.searchPackages(companyId, null, null, null, raw, null, null, PageRequest.of(0, BARCODE_WINDOW))
                .stream()
                .filter(pkg -> pkg.getTrackingBarcode() != null && raw.equalsIgnoreCase(pkg.getTrackingBarcode().strip()))
                .map(PackageResponse::getId)
                .forEach(ids::add);
        return new ArrayList<>(ids);
    }

    private Match matchOf(Long id, Long companyId) {
        CargoPackage pkg;
        try {
            pkg = qrCodes.findById(id, companyId);
        } catch (AppException gone) {
            return null;
        }
        DeliveryStop stop = pkg.getOrder() != null ? pkg.getOrder().getDeliveryStop() : null;
        Trip trip = stop != null ? stop.getTrip() : null;
        return new Match(pkg.getPackageCode(), pkg.getTrackingBarcode(), pkg.getPackageName(), pkg.getStatus(),
                pkg.getFlag(), pkg.getHandlingClass() != null ? pkg.getHandlingClass() : HandlingClass.STANDARD,
                pkg.getActualWeightKg(), pkg.getDestination(),
                trip != null ? trip.getTripCode() : null,
                stop != null ? stop.getStopSequence() : null, stop != null ? stop.getStopName() : null);
    }
}
