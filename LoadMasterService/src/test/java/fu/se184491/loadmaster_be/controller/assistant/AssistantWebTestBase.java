package fu.se184491.loadmaster_be.controller.assistant;

import com.jayway.jsonpath.JsonPath;
import fu.se184491.loadmaster_be.config.assistant.AssistantModelConfig;
import fu.se184491.loadmaster_be.config.assistant.AssistantProperties;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.service.assistant.support.FakeChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;

/**
 * Hai endpoint gọi qua chuỗi bảo mật thật (JWT, bộ lọc khoá gói, @PreAuthorize), giới hạn lượt và DB thật (H2);
 * chỉ mô hình là giả. Người đăng nhập và gói của công ty do test đặt. Hai lớp test dùng chung một ngữ cảnh Spring.
 * Bộ nhớ đệm câu trả lời của khách tắt sẵn (các lớp hỏi lại cùng một câu và đếm lần gọi mô hình); lớp cần nó tự bật.
 */
@SpringBootTest(properties = {"assistant.enabled=true", "assistant.reply-cache.enabled=false"})
@AutoConfigureMockMvc
@Import(AssistantWebTestBase.FakeModelConfig.class)
abstract class AssistantWebTestBase {

    static final String ANSWER = "Chào bạn, mình là Lumo.";
    static final Company COMPANY = Company.builder().id(10L).companyCode("LB").companyName("Vận tải Long Bình").build();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AssistantProperties properties;

    @Autowired
    FakeChatModel model;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CurrentUserService currentUserService;

    @MockitoBean
    CompanySubscriptionRepository companySubscriptionRepository;

    @TestConfiguration
    static class FakeModelConfig {
        @Bean(AssistantModelConfig.MODEL_BEAN)
        FakeChatModel assistantChatModel() {
            return new FakeChatModel().answerAlways(ANSWER, 900, 20);
        }
    }

    /** Lượt gọi tới từ một IP công khai (không qua proxy nội bộ). */
    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    static User dispatcher(long id) {
        return User.builder().id(id).email("dieuphoi" + id + "@longbinh.vn").status(UserStatus.ACTIVE)
                .userRoleType(UserRole.DISPATCHER).company(COMPANY).build();
    }

    void signInAs(User user) {
        when(currentUserService.getCurrentUser()).thenReturn(user);
    }

    void companyPlan(SubscriptionStatus status, LocalDateTime expiresAt) {
        CompanySubscription subscription = CompanySubscription.builder().id(5L).company(COMPANY)
                .plan(SubscriptionPlan.builder().planCode("PRO").name("Pro").build())
                .status(status).expiresAt(expiresAt).build();
        when(companySubscriptionRepository.findByCompanyId(COMPANY.getId())).thenReturn(Optional.of(subscription));
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }
}
