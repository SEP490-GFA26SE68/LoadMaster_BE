package fu.se184491.loadmaster_be.controller.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest;
import fu.se184491.loadmaster_be.dto.request.registration.GuestRegistrationInitRequest;
import fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest;
import fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.registration.GuestRegistrationSessionResponse;
import fu.se184491.loadmaster_be.dto.response.registration.PaymentLinkResponse;
import fu.se184491.loadmaster_be.dto.response.registration.ResendOtpResponse;
import fu.se184491.loadmaster_be.service.registration.GuestRegistrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GuestRegistrationController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class GuestRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private GuestRegistrationService guestRegistrationService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @DisplayName("POST /api/guest-registration/init: trả về 200 khi dữ liệu hợp lệ")
    void initRegistration_success() throws Exception {
        GuestRegistrationInitRequest request = GuestRegistrationInitRequest.builder()
                .email("guest@company.com")
                .fullName("Nguyen Van A")
                .phoneNumber("0987654321")
                .companyName("ABC Logistics")
                .companyCode("ABC_LOG")
                .taxCode("0101234567")
                .build();

        GuestRegistrationSessionResponse response = GuestRegistrationSessionResponse.builder()
                .token("test-token-123")
                .email("guest@company.com")
                .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                .companyName("ABC Logistics")
                .build();

        when(guestRegistrationService.initRegistration(any())).thenReturn(response);

        mockMvc.perform(post("/api/guest-registration/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("test-token-123"))
                .andExpect(jsonPath("$.email").value("guest@company.com"))
                .andExpect(jsonPath("$.status").value("PENDING_EMAIL_VERIFY"));
    }

    @Test
    @DisplayName("POST /api/guest-registration/init: trả về 400 khi dữ liệu không hợp lệ")
    void initRegistration_invalidInput_returns400() throws Exception {
        GuestRegistrationInitRequest request = GuestRegistrationInitRequest.builder()
                .email("not-an-email")
                .build();

        mockMvc.perform(post("/api/guest-registration/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/guest-registration/verify-otp: trả về 200 khi xác thực OTP thành công")
    void verifyOtp_success() throws Exception {
        VerifyOtpRequest request = new VerifyOtpRequest("test-token-123", "123456");

        GuestRegistrationSessionResponse response = GuestRegistrationSessionResponse.builder()
                .token("test-token-123")
                .status(RegistrationStatus.EMAIL_VERIFIED)
                .build();

        when(guestRegistrationService.verifyOtp(any())).thenReturn(response);

        mockMvc.perform(post("/api/guest-registration/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMAIL_VERIFIED"));
    }

    @Test
    @DisplayName("POST /api/guest-registration/resend-otp: trả về 200 khi gửi lại OTP")
    void resendOtp_success() throws Exception {
        ResendOtpResponse response = ResendOtpResponse.builder()
                .message("Đã gửi lại mã OTP")
                .otpExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(guestRegistrationService.resendOtp("test-token-123")).thenReturn(response);

        mockMvc.perform(post("/api/guest-registration/resend-otp")
                        .param("token", "test-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã gửi lại mã OTP"));
    }

    @Test
    @DisplayName("POST /api/guest-registration/accept-policy: trả về 200 khi ký điều khoản")
    void acceptPolicy_success() throws Exception {
        AcceptPolicyRequest request = new AcceptPolicyRequest("test-token-123");

        GuestRegistrationSessionResponse response = GuestRegistrationSessionResponse.builder()
                .token("test-token-123")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .build();

        when(guestRegistrationService.acceptPolicy(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/guest-registration/accept-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POLICY_ACCEPTED"));
    }

    @Test
    @DisplayName("POST /api/guest-registration/init-payment: trả về 200 và link thanh toán")
    void initPayment_success() throws Exception {
        InitPaymentRequest request = new InitPaymentRequest("test-token-123", 1L, BillingCycle.MONTHLY);

        PaymentLinkResponse response = PaymentLinkResponse.builder()
                .paymentUrl("https://sandbox.vnpayment.vn/pay")
                .paymentRef("REG_12345")
                .amountVnd(BigDecimal.valueOf(2_000_000))
                .build();

        when(guestRegistrationService.initPayment(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/guest-registration/init-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentUrl").value("https://sandbox.vnpayment.vn/pay"))
                .andExpect(jsonPath("$.paymentRef").value("REG_12345"));
    }

    @Test
    @DisplayName("GET /api/guest-registration/status/{token}: trả về 200 trạng thái session")
    void getSessionStatus_success() throws Exception {
        GuestRegistrationSessionResponse response = GuestRegistrationSessionResponse.builder()
                .token("test-token-123")
                .status(RegistrationStatus.POLICY_ACCEPTED)
                .build();

        when(guestRegistrationService.getSessionStatus("test-token-123")).thenReturn(response);

        mockMvc.perform(get("/api/guest-registration/status/test-token-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("test-token-123"))
                .andExpect(jsonPath("$.status").value("POLICY_ACCEPTED"));
    }
}
