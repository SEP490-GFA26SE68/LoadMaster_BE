package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import fu.se184491.loadmaster_be.service.support.TrialAccountRequestService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hai màn của nền tảng cho quản trị hệ thống và quản lý nền tảng: Công ty ({@code /nen-tang/cong-ty}, {@code GET
 * /api/companies}) và Đơn dùng thử ({@code /nen-tang/yeu-cau-dung-thu}, {@code GET /api/trial-requests} —
 * {@code SYSTEM_MANAGER}, {@code SYSTEM_ADMIN}). Cùng hàm chỉ đọc của hai màn; hàm đơn dùng thử tự ẩn đơn chưa qua quản
 * lý nền tảng với người chỉ là quản trị hệ thống. Không trả email, số điện thoại, mã số thuế hay mã số nội bộ.
 */
@Component
public class PlatformCompaniesTool implements AssistantTool {

    /** Trạng thái gói tính lúc đọc: chưa có gói, hoặc gói quá hạn mà máy chủ chưa kịp ghi hết hạn. */
    static final String NO_PLAN = "NONE";
    static final String EXPIRED = "EXPIRED";

    private final CurrentUserService currentUser;
    private final CompanyService companies;
    private final TrialAccountRequestService trials;
    private final Clock clock;

    @Autowired
    public PlatformCompaniesTool(CurrentUserService currentUser, CompanyService companies,
                                 TrialAccountRequestService trials) {
        this(currentUser, companies, trials, Clock.systemDefaultZone());
    }

    PlatformCompaniesTool(CurrentUserService currentUser, CompanyService companies,
                          TrialAccountRequestService trials, Clock clock) {
        this.currentUser = currentUser;
        this.companies = companies;
        this.trials = trials;
        this.clock = clock;
    }

    /**
     * Một công ty khách hàng. {@code planStatus}: ACTIVE, TRIAL, CANCELLED (tắt tự gia hạn, còn dùng tới hạn), EXPIRED,
     * hoặc NONE khi chưa có gói.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Company(String companyCode, String companyName, CompanyStatus status, String planName,
                          String planTier, String planStatus, LocalDateTime planExpiresAt, Integer userCount,
                          Integer creditBalance) {
    }

    /** Một đơn đăng ký dùng thử. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrialRequest(String companyName, String interestedPlanName, TrialRequestStatus status,
                               AiPrecheckStatus aiPrecheckStatus, Integer trialDays, Integer trialCredits,
                               String managerReviewedByName, LocalDateTime createdAt) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.SYSTEM_ADMIN, UserRole.SYSTEM_MANAGER);
    }

    @Tool(name = "list_companies", description = """
            Công ty khách hàng của nền tảng như màn Công ty, theo tên, tối đa 30 dòng kèm total là tổng số khớp. \
            Mỗi dòng: mã, tên, trạng thái công ty (ACTIVE hoạt động, SUSPENDED tạm dừng, EXPIRED hết hạn), gói và \
            hạng gói (BASIC, PRO, ULTIMATE), trạng thái gói tính tới lúc này (ACTIVE đang hiệu lực, TRIAL dùng thử, \
            CANCELLED đã huỷ tự gia hạn — còn dùng tới hạn, EXPIRED hết hạn, NONE chưa có gói), hạn gói, số người \
            dùng, số dư credit. Dùng khi hỏi công ty nào hết hạn gói, công ty nào chưa có gói.""")
    public Object listCompanies(
            @ToolParam(required = false, description = "Một phần tên hoặc mã công ty; bỏ trống là mọi công ty")
            String search) {
        String term = search == null || search.isBlank() ? null : search.strip();
        Page<CompanyResponse> page = companies.getAllCompanies(term,
                PageRequest.of(0, ToolResults.LIST_ROWS, Sort.by("companyName")));
        LocalDateTime now = LocalDateTime.now(clock);
        List<Company> rows = page.getContent().stream().map(company -> new Company(company.getCompanyCode(),
                company.getCompanyName(), company.getStatus(), company.getPlanName(), company.getPlanTier(),
                planStatus(company, now), company.getSubscriptionExpiresAt(), company.getUserCount(),
                company.getCreditBalance())).toList();
        return ToolResults.list(rows, page.getTotalElements());
    }

    @Tool(name = "list_trial_requests", description = """
            Đơn đăng ký dùng thử như màn Đơn dùng thử, mới gửi gần nhất trước, tối đa 30 dòng kèm total. Bỏ trống \
            trạng thái thì lấy đơn đang chờ chính người hỏi: quản lý nền tảng là PENDING (chờ thẩm định), quản trị \
            hệ thống là PENDING_ADMIN_CREATION (quản lý đã duyệt, chờ tạo tài khoản). Trạng thái khác: APPROVED đã \
            tạo tài khoản, REJECTED bị từ chối. Quản trị hệ thống không thấy đơn chưa qua quản lý nền tảng. Mỗi \
            dòng: công ty, gói quan tâm, trạng thái, kết quả kiểm tra tự động (PASSED đạt, FAILED không đạt), số \
            ngày và credit dùng thử, người thẩm định, lúc gửi. Bộ lọc sai giá trị trả lỗi INVALID_FILTER.""")
    public Object listTrialRequests(
            @ToolParam(required = false, description = "Trạng thái: PENDING, PENDING_ADMIN_CREATION, APPROVED hoặc REJECTED")
            String status) {
        TrialRequestStatus filter;
        try {
            filter = status == null || status.isBlank() ? waitingFor(currentUser.getCurrentUser())
                    : TrialRequestStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolResults.error(ToolResults.INVALID_FILTER);
        }
        PageResponse<TrialAccountRequestResponse> page = trials.getTrialRequests(null, filter,
                PageRequest.of(0, ToolResults.LIST_ROWS, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<TrialAccountRequestResponse> data = page.getData() == null ? List.of() : page.getData();
        List<TrialRequest> rows = data.stream().map(request -> new TrialRequest(request.getCompanyName(),
                request.getInterestedPlanName(), request.getStatus(), request.getAiPrecheckStatus(),
                request.getTrialDays(), request.getTrialCredits(), request.getManagerReviewedByName(),
                request.getCreatedAt())).toList();
        return ToolResults.list(rows, page.getTotalElements());
    }

    /** Đơn đang chờ việc của người hỏi: quản trị hệ thống tạo tài khoản, quản lý nền tảng thẩm định. */
    private static TrialRequestStatus waitingFor(User user) {
        return user != null && user.getUserRoleType() == UserRole.SYSTEM_ADMIN
                ? TrialRequestStatus.PENDING_ADMIN_CREATION : TrialRequestStatus.PENDING;
    }

    static String planStatus(CompanyResponse company, LocalDateTime now) {
        String status = company.getSubscriptionStatus();
        if (status == null) {
            return NO_PLAN;
        }
        LocalDateTime expiresAt = company.getSubscriptionExpiresAt();
        if (!EXPIRED.equals(status) && expiresAt != null && expiresAt.isBefore(now)) {
            return EXPIRED;
        }
        return status;
    }
}
