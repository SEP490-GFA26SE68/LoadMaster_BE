package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.account.UserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Hồ sơ của người đang hỏi. Mọi vai trò đăng nhập được dùng; gói và số dư chỉ cho ba vai trò đọc được gói của công ty
 * ở ứng dụng (quản trị công ty, quản lý, điều phối viên) — tài xế, nhân viên kho chỉ nhận hồ sơ.
 */
@Component
@RequiredArgsConstructor
public class MyAccountTool implements AssistantTool {

    static final Set<UserRole> BILLING_READERS = EnumSet.of(UserRole.ADMIN, UserRole.MANAGER, UserRole.DISPATCHER);

    private final UserService users;
    private final SubscriptionService subscriptions;

    /** Hồ sơ gửi cho mô hình. {@code subscriptionLocked}: công ty không có gói còn hiệu lực nên bị khoá. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(String fullName, UserRole role, String companyName, Boolean subscriptionLocked,
                          Subscription subscription) {
    }

    /** Gói của công ty; Ultimate không giới hạn credit nên không có số dư. Giờ là giờ Việt Nam. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(String planName, SubscriptionTier tier, SubscriptionStatus status,
                               LocalDateTime expiresAt, Boolean autoRenew, Integer creditsBalance,
                               Integer creditsMonthlyGrant, boolean unlimitedCredits) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.allOf(UserRole.class);
    }

    @Tool(name = "get_my_account", description = """
            Hồ sơ của người đang hỏi: họ tên, vai trò, tên công ty, công ty có đang bị khoá vì không có gói còn \
            hiệu lực không. Quản trị công ty, quản lý, điều phối viên thấy thêm gói đang dùng, hạn gói, tự gia hạn \
            và số dư credit. Dùng khi người hỏi hỏi về chính tài khoản, công ty, gói hay credit của họ.""")
    public Account getMyAccount() {
        UserProfileResponse me = users.getUserProfile(currentJwt());
        boolean hasCompany = me.companyId() != null;
        Subscription subscription = hasCompany && BILLING_READERS.contains(me.userRoleType())
                ? subscriptionOf(subscriptions.getCurrentSubscription(me.companyId()))
                : null;
        return new Account(me.fullName(), me.userRoleType(), me.companyName(),
                hasCompany ? Boolean.TRUE.equals(me.subscriptionExpired()) : null, subscription);
    }

    private static Subscription subscriptionOf(SubscriptionCurrentResponse current) {
        boolean unlimited = current.getTier() == SubscriptionTier.ULTIMATE;
        return new Subscription(current.getPlanName(), current.getTier(), current.getStatus(), current.getExpiresAt(),
                current.getAutoRenew(), unlimited ? null : current.getCreditsBalance(),
                unlimited ? null : current.getCreditsMonthlyGrant(), unlimited);
    }

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken();
        }
        throw new AppException(ErrorCode.UNAUTHENTICATED);
    }
}
