package fu.se184491.loadmaster_be.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
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
import java.util.List;
import java.util.Objects;

/**
 * Bộ lọc kiểm tra trạng thái tài khoản bị gắn cờ (FLAGGED).
 * Nếu tài khoản bị FLAGGED (do quá hạn 72h chưa ký thỏa thuận dùng thử):
 * - Vẫn cho phép đăng nhập và gọi: /api/users/me, /api/agreements/**, /api/support-tickets/**, các public endpoints.
 * - Chặn toàn bộ các API nghiệp vụ khác với mã HTTP 403 Forbidden và mã lỗi ACCOUNT_FLAGGED.
 *
 * <p>Không phải {@code @Component}: {@link WebSecurityConfig} tự dựng bộ lọc và gắn vào chuỗi Spring Security sau
 * {@code BearerTokenAuthenticationFilter}. Là bean thì Spring Boot sẽ đăng ký nó thêm một lần làm servlet filter thường,
 * và mọi lát cắt {@code @WebMvcTest} (không có {@link CurrentUserService}) sẽ không dựng được context.
 */
@Slf4j
public class FlaggedUserEnforcementFilter extends OncePerRequestFilter {

    private static final String FLAGGED_MESSAGE =
            "Tài khoản của bạn đã bị gắn cờ do không ký thỏa thuận dịch vụ đúng hạn 72 giờ. Toàn bộ thông tin và chức năng đã bị khóa.";

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

    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public FlaggedUserEnforcementFilter(CurrentUserService currentUserService, ObjectMapper objectMapper) {
        this.currentUserService = Objects.requireNonNull(currentUserService, "currentUserService");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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
        if (currentUser != null && currentUser.getStatus() == UserStatus.FLAGGED) {
            log.warn("Blocked API call {} {} for FLAGGED user {}",
                    request.getMethod(), request.getRequestURI(), currentUser.getEmail());
            writeFlaggedResponse(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private User findCurrentUser(HttpServletRequest request) {
        try {
            return currentUserService.getCurrentUser();
        } catch (AppException ex) {
            // Token hợp lệ nhưng chưa gắn hồ sơ người dùng: controller tự trả lỗi của nó, bộ lọc không chặn.
            log.debug("Flagged-user check skipped for {}: {}", request.getRequestURI(), ex.getErrorCode());
            return null;
        } catch (RuntimeException ex) {
            log.warn("Flagged-user check failed for {}; request continues without the check",
                    request.getRequestURI(), ex);
            return null;
        }
    }

    private void writeFlaggedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> apiResponse = ApiResponse.error(ErrorCode.ACCOUNT_FLAGGED.name(), FLAGGED_MESSAGE);
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
        // Lối công khai chỉ đọc / gửi liên hệ, giống WebSecurityConfig (chỉ phương thức công khai, không mở lệnh ghi).
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method)
                && (path.equals("/api/vehicle-types") || path.startsWith("/api/subscription/plans"))) {
            return true;
        }
        return HttpMethod.POST.matches(method) && path.equals("/api/contact-guests");
    }
}
