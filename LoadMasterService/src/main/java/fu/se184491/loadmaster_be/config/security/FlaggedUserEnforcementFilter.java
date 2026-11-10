package fu.se184491.loadmaster_be.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
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

/**
 * Bộ lọc kiểm tra trạng thái tài khoản bị gắn cờ (FLAGGED).
 * Nếu tài khoản bị FLAGGED (do quá hạn 72h chưa ký thỏa thuận dùng thử):
 * - Vẫn cho phép đăng nhập và gọi: /api/users/me, /api/agreements/**, các public endpoints.
 * - Chặn toàn bộ các API nghiệp vụ khác với mã HTTP 403 Forbidden và mã lỗi ACCOUNT_FLAGGED.
 */
@Component
@Slf4j
public class FlaggedUserEnforcementFilter extends OncePerRequestFilter {

    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    /**
     * Constructor chính dùng bởi Spring IoC — nhận ObjectMapper bean đã được khai báo trong WebSecurityConfig.
     * Hai tham số đều bắt buộc, không có no-arg constructor do Spring Boot 4+ không cần CGLib proxy cho filters.
     */
    public FlaggedUserEnforcementFilter(CurrentUserService currentUserService, ObjectMapper objectMapper) {
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. Các endpoint được phép gọi khi tài khoản bị FLAGGED:
        if (isWhitelisted(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Kiểm tra nếu request đã được xác thực qua JWT:
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken) {
            try {
                User currentUser = currentUserService.getCurrentUser();
                if (currentUser != null && currentUser.getStatus() == UserStatus.FLAGGED) {
                    log.warn("Blocked API call {} for FLAGGED user {}", path, currentUser.getEmail());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");

                    ApiResponse<Void> apiResponse = ApiResponse.error(
                            ErrorCode.ACCOUNT_FLAGGED.name(),
                            "Tài khoản của bạn đã bị gắn cờ do không ký thỏa thuận dịch vụ đúng hạn 72 giờ. Toàn bộ thông tin và chức năng đã bị khóa."
                    );

                    response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
                    return;
                }
            } catch (Exception ex) {
                // Nếu không tìm thấy profile hoặc lỗi phụ, để filter chain xử lý tiếp
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isWhitelisted(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/api/users/me")
                || path.startsWith("/api/agreements/")
                || path.startsWith("/api/support-tickets")  // cho phép gửi ticket mở khóa và xem trạng thái ticket
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api/auth/")
                || path.startsWith("/api/guest-registration/")
                || path.startsWith("/api/trial-registration/")
                || path.startsWith("/webhook/")
                || path.startsWith("/api/payment/");
    }
}

