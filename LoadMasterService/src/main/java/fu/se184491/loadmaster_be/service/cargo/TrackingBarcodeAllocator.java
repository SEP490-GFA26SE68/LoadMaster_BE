package fu.se184491.loadmaster_be.service.cargo;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Cấp mã vạch ({@code tracking_barcode}) cho kiện mới tạo, để kho và tài xế gõ hoặc quét được mã
 * thay cho mã số nội bộ.
 *
 * <p>Mã vạch là chính mã kiện vật lý ({@code package_code}: mã gốc khi tạo một kiện,
 * {@code <mã gốc>-001}, {@code -002}… khi tạo nhiều kiện). Mã vạch là duy nhất toàn hệ thống:
 * trùng mã vạch đã có (dữ liệu cũ) hoặc mã đã cấp trong cùng lô thì thêm hậu tố {@code -2},
 * {@code -3}… Dùng một đối tượng cho một lô.</p>
 */
public final class TrackingBarcodeAllocator {

    /** Độ dài cột {@code packages.tracking_barcode}. */
    static final int MAX_LENGTH = 100;

    private final Predicate<String> existsInDatabase;
    private final Set<String> allocated = new HashSet<>();

    public TrackingBarcodeAllocator(Predicate<String> existsInDatabase) {
        this.existsInDatabase = existsInDatabase;
    }

    /** Mã vạch cho kiện có mã {@code packageCode}; null khi kiện không có mã. */
    public String allocate(String packageCode) {
        if (packageCode == null || packageCode.isBlank()) {
            return null;
        }
        String base = packageCode.trim();
        String candidate = fit(base, "");
        int suffix = 2;
        while (taken(candidate)) {
            candidate = fit(base, "-" + suffix++);
        }
        allocated.add(candidate.toUpperCase(Locale.ROOT));
        return candidate;
    }

    private boolean taken(String candidate) {
        return allocated.contains(candidate.toUpperCase(Locale.ROOT)) || existsInDatabase.test(candidate);
    }

    private static String fit(String base, String suffix) {
        int room = MAX_LENGTH - suffix.length();
        return (base.length() > room ? base.substring(0, room) : base) + suffix;
    }
}
