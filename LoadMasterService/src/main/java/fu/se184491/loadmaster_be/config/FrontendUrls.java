package fu.se184491.loadmaster_be.config;

import java.util.Arrays;
import java.util.List;

/**
 * Địa chỉ của web app: dùng cho CORS, chuyển hướng sau thanh toán và đường dẫn trong email / hoá đơn.
 * Đặt bằng biến môi trường {@code FRONTEND_URL} (và {@code CORS_ALLOWED_ORIGINS} khi cần nhiều origin); vắng thì là máy dev.
 */
public final class FrontendUrls {

    public static final String DEFAULT = "http://localhost:5173";

    /** Biểu thức {@code @Value} của địa chỉ web app. */
    public static final String URL = "${app.frontend.url:" + DEFAULT + "}";

    /** Biểu thức {@code @Value} của các origin được gọi API (ngăn bằng dấu phẩy). */
    public static final String CORS_ORIGINS = "${app.frontend.cors-origins:" + DEFAULT + "}";

    private FrontendUrls() {
    }

    /** Ghép đường dẫn vào địa chỉ web app, bỏ dấu "/" thừa ở cuối địa chỉ. */
    public static String join(String baseUrl, String path) {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /** Tách danh sách origin ngăn bằng dấu phẩy, bỏ khoảng trắng và mục rỗng. */
    public static List<String> origins(String commaSeparated) {
        List<String> origins = commaSeparated == null ? List.of() : Arrays.stream(commaSeparated.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
        return origins.isEmpty() ? List.of(DEFAULT) : origins;
    }
}
