package fu.se184491.loadmaster_be.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlaggedUserEnforcementFilterTest {

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private FilterChain filterChain;

    private FlaggedUserEnforcementFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new FlaggedUserEnforcementFilter(currentUserService, objectMapper, companySubscriptionRepository);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockAuthentication() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Collections.singletonMap("alg", "none"),
                Collections.singletonMap("sub", "kc-user-1")
        );
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Chặn (403) request nghiệp vụ /api/trips khi user có trạng thái FLAGGED")
    void doFilter_shouldBlockBusinessApiWhenUserFlagged() throws Exception {
        mockAuthentication();
        User flaggedUser = User.builder()
                .id(1L)
                .email("trial@company.vn")
                .status(UserStatus.FLAGGED)
                .userRoleType(UserRole.ADMIN)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(flaggedUser);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("ACCOUNT_FLAGGED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Cho phép request /api/users/me khi user có trạng thái FLAGGED để đọc hồ sơ")
    void doFilter_shouldAllowUsersMeWhenUserFlagged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(currentUserService, never()).getCurrentUser();
    }

    @Test
    @DisplayName("Cho phép request /api/agreements/sign khi user có trạng thái FLAGGED để ký thỏa thuận")
    void doFilter_shouldAllowAgreementSignWhenUserFlagged() throws Exception {
        mockAuthentication();
        User flaggedUser = User.builder()
                .id(1L)
                .email("trial@company.vn")
                .status(UserStatus.FLAGGED)
                .userRoleType(UserRole.ADMIN)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(flaggedUser);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agreements/sign");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Cho phép mọi request nghiệp vụ khi user có trạng thái ACTIVE")
    void doFilter_shouldAllowAllWhenUserActive() throws Exception {
        mockAuthentication();
        User activeUser = User.builder()
                .id(1L)
                .email("active@company.vn")
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.ADMIN)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(activeUser);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Cho phép POST /api/support-tickets khi user FLAGGED để gửi ticket mở khóa")
    void doFilter_shouldAllowSupportTicketsWhenUserFlagged() throws Exception {
        mockAuthentication();
        User flaggedUser = User.builder()
                .id(1L)
                .email("trial@company.vn")
                .status(UserStatus.FLAGGED)
                .userRoleType(UserRole.ADMIN)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(flaggedUser);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/support-tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Cho phép POST /api/support-tickets/{id}/unlock-trial khi user FLAGGED (vẫn trong whitelist)")
    void doFilter_shouldAllowSupportTicketUnlockWhenUserFlagged() throws Exception {
        mockAuthentication();
        User flaggedUser = User.builder()
                .id(1L)
                .email("trial@company.vn")
                .status(UserStatus.FLAGGED)
                .userRoleType(UserRole.ADMIN)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(flaggedUser);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/support-tickets/42/unlock-trial");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Gói cước EXPIRED: Cho phép Company Admin truy cập /api/subscription/current và các endpoint Gói & Credit")
    void doFilter_shouldAllowBillingAndSupportForAdminWhenSubscriptionExpired() throws Exception {
        mockAuthentication();
        Company company = Company.builder().id(10L).companyCode("GAMMA-TRIAL").build();
        User adminUser = User.builder()
                .id(1L)
                .email("admin@gamma.vn")
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.ADMIN)
                .company(company)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(adminUser);

        CompanySubscription sub = CompanySubscription.builder()
                .id(5L)
                .company(company)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(companySubscriptionRepository.findByCompanyId(10L)).thenReturn(Optional.of(sub));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscription/current");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Gói cước EXPIRED: Chặn (403 SUBSCRIPTION_EXPIRED) Company Admin khi truy cập /api/trips")
    void doFilter_shouldBlockTripsForAdminWhenSubscriptionExpired() throws Exception {
        mockAuthentication();
        Company company = Company.builder().id(10L).companyCode("GAMMA-TRIAL").build();
        User adminUser = User.builder()
                .id(1L)
                .email("admin@gamma.vn")
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.ADMIN)
                .company(company)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(adminUser);

        CompanySubscription sub = CompanySubscription.builder()
                .id(5L)
                .company(company)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(companySubscriptionRepository.findByCompanyId(10L)).thenReturn(Optional.of(sub));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("SUBSCRIPTION_EXPIRED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Gói cước EXPIRED: Chặn (403 SUBSCRIPTION_EXPIRED) Manager khi truy cập /api/trips")
    void doFilter_shouldBlockTripsForManagerWhenSubscriptionExpired() throws Exception {
        mockAuthentication();
        Company company = Company.builder().id(10L).companyCode("GAMMA-TRIAL").build();
        User managerUser = User.builder()
                .id(2L)
                .email("manager@gamma.vn")
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.MANAGER)
                .company(company)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(managerUser);

        CompanySubscription sub = CompanySubscription.builder()
                .id(5L)
                .company(company)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(companySubscriptionRepository.findByCompanyId(10L)).thenReturn(Optional.of(sub));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("SUBSCRIPTION_EXPIRED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Gói cước EXPIRED: Chặn (403 SUBSCRIPTION_EXPIRED) Dispatcher khi cố truy cập /api/subscription/current (chỉ Admin mới được truy cập)")
    void doFilter_shouldBlockSubscriptionApiForNonAdminWhenSubscriptionExpired() throws Exception {
        mockAuthentication();
        Company company = Company.builder().id(10L).companyCode("GAMMA-TRIAL").build();
        User dispatcherUser = User.builder()
                .id(3L)
                .email("dispatcher@gamma.vn")
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.DISPATCHER)
                .company(company)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(dispatcherUser);

        CompanySubscription sub = CompanySubscription.builder()
                .id(5L)
                .company(company)
                .status(SubscriptionStatus.EXPIRED)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();
        when(companySubscriptionRepository.findByCompanyId(10L)).thenReturn(Optional.of(sub));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/subscription/current");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("SUBSCRIPTION_EXPIRED");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Platform Roles: Không bị ảnh hưởng kể cả khi kiểm tra subscription")
    void doFilter_shouldAllowPlatformRolesAlways() throws Exception {
        mockAuthentication();
        User sysAdmin = User.builder()
                .id(99L)
                .email("sysadmin@loadmaster.vn")
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.SYSTEM_ADMIN)
                .build();
        when(currentUserService.getCurrentUser()).thenReturn(sysAdmin);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(companySubscriptionRepository, never()).findByCompanyId(any());
    }
}

