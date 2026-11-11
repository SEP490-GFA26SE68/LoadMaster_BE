package fu.se184491.loadmaster_be.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
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
 */
@Component
@Slf4j
public class FlaggedUserEnforcementFilter extends OncePerRequestFilter {

    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final CompanySubscriptionRepository companySubscriptionRepository;

    public FlaggedUserEnforcementFilter(
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            CompanySubscriptionRepository companySubscriptionRepository
    ) {
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.companySubscriptionRepository = companySubscriptionRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. Endpoint dùng chung luôn mở cho mọi người dùng (để app đọc được hồ sơ, trạng thái cờ, hết hạn):
        if (isAlwaysAllowed(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Kiểm tra nếu request đã được xác thực qua JWT:
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken) {
            try {
                User currentUser = currentUserService.getCurrentUser();
                if (currentUser != null) {
                    // A. Kiểm tra tài khoản bị FLAGGED (72h chưa ký thỏa thuận):
                    if (currentUser.getStatus() == UserStatus.FLAGGED) {
                        if (isFlaggedWhitelisted(path)) {
                            filterChain.doFilter(request, response);
                            return;
                        }

                        log.warn("Blocked API call {} for FLAGGED user {}", path, currentUser.getEmail());
                        sendForbiddenResponse(response, ErrorCode.ACCOUNT_FLAGGED.name(),
                                "Tài khoản của bạn đã bị gắn cờ do không ký thỏa thuận dịch vụ đúng hạn 72 giờ. Toàn bộ thông tin và chức năng đã bị khóa.");
                        return;
                    }

                    // B. Kiểm tra gói cước Doanh nghiệp hết hạn (Subscription EXPIRED):
                    if (currentUser.getCompany() != null && !isPlatformRole(currentUser.getUserRoleType())) {
                        boolean isSubscriptionExpired = checkSubscriptionExpired(currentUser.getCompany().getId());

                        if (isSubscriptionExpired) {
                            if (currentUser.getUserRoleType() == UserRole.ADMIN) {
                                // Company Admin: Cho phép các endpoint Gói & Credit + Hỗ trợ
                                if (!isBillingAndSupportWhitelisted(path)) {
                                    log.warn("Blocked API call {} for Company Admin {} due to EXPIRED subscription", path, currentUser.getEmail());
                                    sendForbiddenResponse(response, ErrorCode.SUBSCRIPTION_EXPIRED.name(),
                                            "Gói cước dịch vụ của doanh nghiệp đã hết hạn. Vui lòng nâng cấp hoặc gia hạn gói cước tại mục Gói và Credit để tiếp tục sử dụng.");
                                    return;
                                }
                            } else {
                                // Các vai trò nhân viên khác (MANAGER, DISPATCHER, DRIVER, WAREHOUSE_WORKER):
                                // Khóa hoàn toàn, chỉ cho phép /api/users/me và public endpoints
                                if (!isNonAdminWhitelisted(path)) {
                                    log.warn("Blocked API call {} for employee {} due to EXPIRED company subscription", path, currentUser.getEmail());
                                    sendForbiddenResponse(response, ErrorCode.SUBSCRIPTION_EXPIRED.name(),
                                            "Gói cước dùng thử của doanh nghiệp đã hết hạn. Vui lòng liên hệ Quản trị viên công ty (Company Admin) để nâng cấp hoặc gia hạn gói cước.");
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                // Nếu không tìm thấy profile hoặc lỗi phụ, để filter chain xử lý tiếp
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAlwaysAllowed(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/users/me") || isPublicPath(path);
    }

    private boolean checkSubscriptionExpired(Long companyId) {
        if (companySubscriptionRepository == null) {
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

    private boolean isPlatformRole(UserRole role) {
        return role == UserRole.SYSTEM_ADMIN
                || role == UserRole.SYSTEM_MANAGER
                || role == UserRole.SYSTEM_SUPPORTER;
    }

    private boolean isFlaggedWhitelisted(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/users/me")
                || path.startsWith("/api/agreements")
                || path.startsWith("/api/support-tickets")
                || isPublicPath(path);
    }

    private boolean isBillingAndSupportWhitelisted(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/users/me")
                || path.startsWith("/api/subscription")
                || path.startsWith("/api/credits")
                || path.startsWith("/api/invoices")
                || path.startsWith("/api/payment")
                || path.startsWith("/api/support-tickets")
                || path.startsWith("/api/agreements")
                || path.startsWith("/api/notifications")
                || isPublicPath(path);
    }

    private boolean isNonAdminWhitelisted(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/users/me") || isPublicPath(path);
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/guest-registration/")
                || path.startsWith("/api/trial-registration/")
                || path.startsWith("/webhook/")
                || path.startsWith("/api/payment/");
    }

    private void sendForbiddenResponse(HttpServletResponse response, String errorCode, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }
}

