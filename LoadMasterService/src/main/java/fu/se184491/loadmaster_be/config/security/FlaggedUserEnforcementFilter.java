package fu.se184491.loadmaster_be.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bộ lọc kiểm tra trạng thái tài khoản bị gắn cờ (FLAGGED) và trạng thái hết hạn gói cước của Doanh nghiệp.
 *
 * 1. Nếu tài khoản bị FLAGGED (quá hạn 72h chưa ký thỏa thuận dùng thử):
 *    - Chỉ cho phép: /api/users/me, /api/agreements/**, /api/support-tickets/**, các public endpoints.
 *    - Chặn toàn bộ API khác với HTTP 403 ACCOUNT_FLAGGED.
 *
 * 2. Nếu gói cước của Doanh nghiệp bị EXPIRED (hết hạn dùng thử hoặc hết hạn hợp đồng):
 *    - Company Admin (ADMIN): Chỉ cho phép các API thuộc phân hệ Gói & Credit (/api/subscription/**,
 *      /api/credits/**, /api/invoices/**, /api/payment/**, /api/support-tickets/**, /api/users/me, /api/agreements/**, /api/notifications/**).
 *      Chặn toàn bộ các API nghiệp vụ khác (trips, vehicles, cargo,...) với HTTP 403 SUBSCRIPTION_EXPIRED.
 *    - Các nhân viên khác (MANAGER, DISPATCHER, DRIVER, WAREHOUSE_WORKER): Khóa toàn diện, chỉ cho phép
 *      /api/users/me và public endpoints để đọc hồ sơ và hiển thị màn hình khóa. Chặn mọi API nghiệp vụ khác
 *      với HTTP 403 SUBSCRIPTION_EXPIRED.
 *
 * <p>Không phải {@code @Component}: {@link WebSecurityConfig} tự dựng bộ lọc và gắn vào chuỗi Spring Security sau
 * {@code BearerTokenAuthenticationFilter}. Là bean thì Spring Boot sẽ đăng ký nó thêm một lần làm servlet filter thường,
 * và mọi lát cắt {@code @WebMvcTest} (không có {@link CurrentUserService}) sẽ không dựng được context.
 */
@Slf4j
public class FlaggedUserEnforcementFilter extends OncePerRequestFilter {

    private static final String FLAGGED_MESSAGE =
            "Tài khoản của bạn đã bị gắn cờ do không ký thỏa thuận dịch vụ đúng hạn 72 giờ. Toàn bộ thông tin và chức năng đã bị khóa.";

    private static final String SUBSCRIPTION_EXPIRED_ADMIN_MESSAGE =
            "Gói cước dịch vụ của doanh nghiệp đã hết hạn. Vui lòng nâng cấp hoặc gia hạn gói cước tại mục Gói và Credit để tiếp tục sử dụng.";

    private static final String SUBSCRIPTION_EXPIRED_EMPLOYEE_MESSAGE =
            "Gói cước dùng thử của doanh nghiệp đã hết hạn. Vui lòng liên hệ Quản trị viên công ty (Company Admin) để nâng cấp hoặc gia hạn gói cước.";

    /** Tiền tố đường dẫn luôn đi qua, kể cả khi tài khoản bị gắn cờ (public endpoint hoặc lối để ký / xin mở khóa). */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/api/agreements/",
            "/api/support-tickets",  // cho phép gửi ticket mở khóa và xem trạng thái ticket
            "/v3/api-docs",
            "/swagger-ui",
            "/api/auth/",
            "/api/guest-registration/",
            "/api/trial-registration/",
            "/webhook/",
            "/api/payment/"
    );

    /** Tiền tố đường dẫn được phép cho Company Admin khi gói dịch vụ hết hạn. */
    private static final List<String> BILLING_AND_SUPPORT_PREFIXES = List.of(
            "/api/subscription",
            "/api/credits",
            "/api/invoices",
            "/api/payment",
            "/api/support-tickets",
            "/api/agreements",
            "/api/notifications"
    );

    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final CompanySubscriptionRepository companySubscriptionRepository;

    public FlaggedUserEnforcementFilter(
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            CompanySubscriptionRepository companySubscriptionRepository
    ) {
        this.currentUserService = Objects.requireNonNull(currentUserService, "currentUserService");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.companySubscriptionRepository = companySubscriptionRepository;
    }

    public FlaggedUserEnforcementFilter(CurrentUserService currentUserService, ObjectMapper objectMapper) {
        this(currentUserService, objectMapper, null);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Chỉ request đã xác thực bằng JWT mới có người dùng để kiểm; request ẩn danh và đường dẫn được phép
        // đi thẳng, không truy vấn DB.
        if (isAllowed(request) || !isJwtAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        User currentUser = findCurrentUser(request);
        if (currentUser != null) {
            // A. Kiểm tra tài khoản bị FLAGGED (72h chưa ký thỏa thuận):
            if (currentUser.getStatus() == UserStatus.FLAGGED) {
                log.warn("Blocked API call {} {} for FLAGGED user {}",
                        request.getMethod(), request.getRequestURI(), currentUser.getId());
                writeForbiddenResponse(response, ErrorCode.ACCOUNT_FLAGGED.name(), FLAGGED_MESSAGE);
                return;
            }

            // B. Kiểm tra gói cước Doanh nghiệp hết hạn (Subscription EXPIRED):
            if (currentUser.getCompany() != null && !isPlatformRole(currentUser.getUserRoleType())) {
                boolean isSubscriptionExpired = checkSubscriptionExpired(currentUser.getCompany().getId());

                if (isSubscriptionExpired) {
                    String path = request.getRequestURI();
                    if (currentUser.getUserRoleType() == UserRole.ADMIN) {
                        // Company Admin: Cho phép các endpoint Gói & Credit + Hỗ trợ
                        if (!isBillingAndSupportAllowed(path)) {
                            log.warn("Blocked API call {} {} for Company Admin {} due to EXPIRED subscription",
                                    request.getMethod(), path, currentUser.getId());
                            writeForbiddenResponse(response, ErrorCode.SUBSCRIPTION_EXPIRED.name(), SUBSCRIPTION_EXPIRED_ADMIN_MESSAGE);
                            return;
                        }
                    } else {
                        // Các vai trò nhân viên khác (MANAGER, DISPATCHER, DRIVER, WAREHOUSE_WORKER): Khóa hoàn toàn
                        log.warn("Blocked API call {} {} for employee {} due to EXPIRED company subscription",
                                request.getMethod(), path, currentUser.getId());
                        writeForbiddenResponse(response, ErrorCode.SUBSCRIPTION_EXPIRED.name(), SUBSCRIPTION_EXPIRED_EMPLOYEE_MESSAGE);
                        return;
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private User findCurrentUser(HttpServletRequest request) {
        try {
            return currentUserService.getCurrentUser();
        } catch (AppException ex) {
            // Token hợp lệ nhưng chưa gắn hồ sơ người dùng: controller tự trả lỗi của nó, bộ lọc không chặn.
            log.debug("Flagged/Subscription check skipped for {}: {}", request.getRequestURI(), ex.getErrorCode());
            return null;
        } catch (RuntimeException ex) {
            log.warn("User lookup failed for {}; request continues without the check",
                    request.getRequestURI(), ex);
            return null;
        }
    }

    private boolean checkSubscriptionExpired(Long companyId) {
        if (companySubscriptionRepository == null || companyId == null) {
            return false;
        }
        Optional<CompanySubscription> subOpt = companySubscriptionRepository.findByCompanyId(companyId);
        if (subOpt.isEmpty()) {
            return true;
        }
        CompanySubscription sub = subOpt.get();
        return sub.getStatus() == SubscriptionStatus.EXPIRED
                || (sub.getExpiresAt() != null && sub.getExpiresAt().isBefore(LocalDateTime.now()));
    }

    private static boolean isPlatformRole(UserRole role) {
        return role == UserRole.SYSTEM_ADMIN
                || role == UserRole.SYSTEM_MANAGER
                || role == UserRole.SYSTEM_SUPPORTER;
    }

    private static boolean isBillingAndSupportAllowed(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : BILLING_AND_SUPPORT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void writeForbiddenResponse(HttpServletResponse response, String errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }

    /** BearerTokenAuthenticationFilter chỉ đặt JwtAuthenticationToken vào context khi token hợp lệ. */
    private static boolean isJwtAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken;
    }

    private static boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        if (path.equals("/api/users/me") || path.equals("/swagger-ui.html")) {
            return true;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        // Lối công khai chỉ đọc bảng giá / gửi liên hệ, giống WebSecurityConfig (chỉ phương thức công khai, không mở lệnh ghi).
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method)
                && path.startsWith("/api/subscription/plans")) {
            return true;
        }
        return HttpMethod.POST.matches(method) && path.equals("/api/contact-guests");
    }
}
