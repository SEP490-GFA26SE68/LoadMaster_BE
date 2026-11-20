package fu.se184491.loadmaster_be.controller.assistant;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.service.assistant.briefing.BriefingFacts;
import fu.se184491.loadmaster_be.service.assistant.briefing.BriefingFactsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/assistant/briefing qua chuỗi bảo mật thật: chỉ điều phối viên và quản lý công ty, vẫn bị khoá theo gói. */
class AssistantBriefingControllerTest extends AssistantWebTestBase {

    private static final String URL = "/api/assistant/briefing";

    @MockitoBean
    BriefingFactsCollector collector;

    @BeforeEach
    void activePlan() {
        companyPlan(SubscriptionStatus.ACTIVE, LocalDateTime.now().plusDays(20));
    }

    private static User withRole(long id, UserRole role) {
        return User.builder().id(id).email("nguoi" + id + "@longbinh.vn").status(UserStatus.ACTIVE)
                .userRoleType(role).company(COMPANY).build();
    }

    @Test
    void quietDayAnswersTheFixedSentenceAndNoLinks() throws Exception {
        signInAs(withRole(21L, UserRole.MANAGER));
        when(collector.collect(eq(COMPANY.getId()), eq(UserRole.MANAGER), any()))
                .thenReturn(new BriefingFacts(Map.of(), 0, 0, 0, 0, null, null, 3000L, 750L, List.of()));

        mockMvc.perform(get(URL).param("locale", "vi")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.generatedAt").isString())
                .andExpect(jsonPath("$.data.bullets.length()").value(1))
                .andExpect(jsonPath("$.data.bullets[0]").value("Hôm nay không có việc cần chú ý."))
                .andExpect(jsonPath("$.data.links.length()").value(0));
    }

    @Test
    void busyDayHasModelBulletsAndLinksAllowedForTheRole() throws Exception {
        signInAs(dispatcher(22L));
        when(collector.collect(eq(COMPANY.getId()), eq(UserRole.DISPATCHER), any()))
                .thenReturn(new BriefingFacts(Map.of(TripStatus.IN_TRANSIT, 1L), 0, 1, 0, 0, 0L, 0L, 3000L, 750L,
                        List.of(new BriefingFacts.AttentionTrip("TRIP-LB-0007", 0, 1, 0, 0, false, false))));

        // Câu mặc định của mô hình giả không phải JSON có cấu trúc: lỗi có mã, và không vào bộ đệm
        mockMvc.perform(get(URL).param("locale", "vi")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));

        model.reply("{\"bullets\":[\"TRIP-LB-0007 có 1 sự cố chưa xử lý.\"]}", 600, 40);
        mockMvc.perform(get(URL).param("locale", "vi")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bullets[0]").value("TRIP-LB-0007 có 1 sự cố chưa xử lý."))
                .andExpect(jsonPath("$.data.links[0].to").value("/giam-sat"))
                .andExpect(jsonPath("$.data.links[1].to").value("/chuyen/TRIP-LB-0007"))
                .andExpect(jsonPath("$.data.links[1].label").value("Chuyến TRIP-LB-0007"))
                .andExpect(jsonPath("$.data.links[2].to").value("/chuyen"));
    }

    @Test
    void driverIsForbidden() throws Exception {
        signInAs(withRole(23L, UserRole.DRIVER));

        mockMvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DRIVER"))))
                .andExpect(status().isForbidden());
        verify(collector, never()).collect(anyLong(), any(), any());
    }

    @Test
    void roleIsReadFromTheServerNotFromTheToken() throws Exception {
        // Token mang vai trò điều phối viên nhưng người dùng trong máy chủ là quản trị công ty
        signInAs(withRole(24L, UserRole.ADMIN));

        mockMvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(collector, never()).collect(anyLong(), any(), any());
    }

    @Test
    void withoutTokenIsUnauthorizedAndExpiredPlanIsLocked() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());

        signInAs(dispatcher(25L));
        companyPlan(SubscriptionStatus.EXPIRED, LocalDateTime.now().minusDays(1));
        mockMvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"));
    }
}
