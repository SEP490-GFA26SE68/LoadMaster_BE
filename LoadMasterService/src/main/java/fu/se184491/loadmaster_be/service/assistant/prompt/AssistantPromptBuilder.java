package fu.se184491.loadmaster_be.service.assistant.prompt;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.assistant.AssistantChannel;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import fu.se184491.loadmaster_be.service.assistant.model.PageContext;
import fu.se184491.loadmaster_be.service.assistant.model.ViewerContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Hai phần của prompt. {@link #systemText} là luật + kiến thức, không đổi giữa các lượt nên được đệm. {@link #turnContext}
 * là khối dữ liệu của lượt này, dựng bằng code từ trường đã kiểm, đi trong lượt người dùng — không bao giờ ở phần hệ
 * thống. Chữ trình duyệt gửi mà không kiểm được ({@code page.title}) không vào prompt.
 */
@Component
public class AssistantPromptBuilder {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Lệch kế hoạch: kế hoạch ghi [a-z0-9/_-]; đường dẫn chi tiết chuyến mang mã chuyến viết hoa (/chuyen/TRIP-…)
    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9/_-]{0,120}$");
    private static final Pattern TRIP_CODE = Pattern.compile("^TRIP-[A-Z0-9-]{1,40}$");
    private static final Pattern NUMERIC_ID = Pattern.compile("^[0-9]{1,19}$");
    private static final Pattern PACKAGE_CODE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final String publicSystemText;
    private final String appSystemText;

    public AssistantPromptBuilder() {
        String rules = read("assistant/rules.md");
        String knowledge = read("assistant/knowledge.md");
        this.publicSystemText = rules + "\n\n" + knowledge;
        this.appSystemText = publicSystemText + "\n\n" + read("assistant/knowledge-app.md");
    }

    public String systemText(AssistantChannel channel) {
        return channel == AssistantChannel.APP ? appSystemText : publicSystemText;
    }

    /** Khối NGỮ CẢNH LƯỢT NÀY; trường sai dạng bị bỏ, không báo lỗi. */
    public String turnContext(AssistantCaller caller, PageContext page, ViewerContext viewer, String locale, Instant now) {
        StringBuilder out = new StringBuilder("<NGỮ CẢNH LƯỢT NÀY>\n");
        line(out, "Hôm nay", DATE.format(now.atZone(VIETNAM)));
        line(out, "Ngôn ngữ giao diện", normalizeLocale(locale));
        if (caller == null) {
            line(out, "Người hỏi", "khách chưa đăng nhập, ở trang công khai");
        } else {
            line(out, "Vai trò", roleLabel(caller.role()));
            line(out, "Công ty", oneLine(caller.companyName()));
            line(out, "Gói", oneLine(caller.planName()));
        }
        if (page != null) {
            line(out, "Màn đang mở", matching(PATH, page.path()));
        }
        if (viewer != null) {
            line(out, "Mã chuyến đang xem", matching(TRIP_CODE, viewer.tripCode()));
            line(out, "Mã phương án đang xem", matching(NUMERIC_ID, viewer.loadPlanId()));
            line(out, "Mã kiện đang chọn", matching(PACKAGE_CODE, viewer.selectedPackageCode()));
        }
        return out.append("</NGỮ CẢNH LƯỢT NÀY>").toString();
    }

    public static String normalizeLocale(String locale) {
        return "en".equalsIgnoreCase(locale) ? "en" : "vi";
    }

    static String roleLabel(UserRole role) {
        if (role == null) {
            return null;
        }
        return switch (role) {
            case SYSTEM_ADMIN -> "Quản trị hệ thống";
            case SYSTEM_MANAGER -> "Quản lý nền tảng";
            case SYSTEM_SUPPORTER -> "Hỗ trợ khách hàng";
            case ADMIN -> "Quản trị công ty";
            case MANAGER -> "Quản lý công ty";
            case DISPATCHER -> "Điều phối viên";
            case DRIVER -> "Tài xế";
            case WAREHOUSE_WORKER -> "Nhân viên kho";
        };
    }

    private static void line(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String matching(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches() ? value : null;
    }

    /** Tên công ty, tên gói đọc từ DB; vẫn gộp thành một dòng để không mở được khối mới. */
    private static String oneLine(String value) {
        if (value == null) {
            return null;
        }
        String flat = value.replaceAll("[\\r\\n<>]+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Thiếu tài nguyên của trợ lý: " + path, e);
        }
    }
}
