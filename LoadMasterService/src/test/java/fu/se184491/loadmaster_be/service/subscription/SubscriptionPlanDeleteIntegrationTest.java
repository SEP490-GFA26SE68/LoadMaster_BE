package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Xoá gói trên cơ sở dữ liệu thật (H2): đăng ký ở mọi trạng thái còn trỏ tới gói thì trả PLAN_IN_USE, không vấp khoá ngoại. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:subscription-plan-delete;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.security.oauth2.client.registration.optimization-client.client-secret=test",
        "spring.security.oauth2.client.registration.keycloak-admin-client.client-secret=test",
        "app.brevo.api-key=test",
        "app.brevo.sender-email=test@example.com",
        "app.brevo.sender-name=Test",
        "app.brevo.security.otp-hmac-secret=test-secret"
})
@Transactional
class SubscriptionPlanDeleteIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SubscriptionPlanService subscriptionPlanService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @ParameterizedTest
    @EnumSource(SubscriptionStatus.class)
    @DisplayName("Gói còn đăng ký ở bất kỳ trạng thái nào trỏ tới: PLAN_IN_USE, gói vẫn còn")
    void deletePlan_withSubscriptionInAnyStatus_isPlanInUse(SubscriptionStatus status) {
        SubscriptionPlan plan = persistPlan("TEST-" + status.name());
        Company company = persistCompany("CO-" + status.name());
        entityManager.persist(CompanySubscription.builder()
                .company(company)
                .plan(plan)
                .status(status)
                .startedAt(LocalDateTime.now().minusDays(60))
                .expiresAt(LocalDateTime.now().minusDays(30))
                .build());
        entityManager.flush();

        AppException ex = assertThrows(AppException.class, () -> subscriptionPlanService.deletePlan(plan.getId()));
        assertEquals(ErrorCode.PLAN_IN_USE, ex.getErrorCode());

        entityManager.flush();
        entityManager.clear();
        assertNotNull(entityManager.find(SubscriptionPlan.class, plan.getId()));
    }

    @Test
    @DisplayName("Gói không còn đăng ký nào trỏ tới: xoá được")
    void deletePlan_withoutSubscription_deletes() {
        SubscriptionPlan plan = persistPlan("TEST-UNUSED");
        entityManager.flush();

        subscriptionPlanService.deletePlan(plan.getId());

        entityManager.flush();
        entityManager.clear();
        assertNull(entityManager.find(SubscriptionPlan.class, plan.getId()));
    }

    private SubscriptionPlan persistPlan(String code) {
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .planCode(code)
                .name("Gói thử " + code)
                .price(new BigDecimal("1000000"))
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(100)
                .active(false)
                .build();
        entityManager.persist(plan);
        return plan;
    }

    private Company persistCompany(String code) {
        Company company = Company.builder()
                .companyCode(code)
                .companyName("Công ty " + code)
                .taxCode("TAX-" + code)
                .billingEmail(code.toLowerCase() + "@example.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(company);
        return company;
    }
}
