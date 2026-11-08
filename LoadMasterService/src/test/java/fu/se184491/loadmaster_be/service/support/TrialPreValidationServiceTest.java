package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.service.support.impl.TrialPreValidationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrialPreValidationServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TrialAccountRequestRepository trialAccountRequestRepository;

    @Mock
    private BrevoClient brevoClient;

    @InjectMocks
    private TrialPreValidationServiceImpl trialPreValidationService;

    private TrialAccountRequest testRequest;

    @BeforeEach
    void setUp() {
        testRequest = TrialAccountRequest.builder()
                .id(1L)
                .fullName("Nguyen Van A")
                .email("test@example.com")
                .companyName("Cong Ty TNHH Test")
                .taxCode("0123456789")
                .companyAddress("123 Duong ABC, Quan 1, TP HCM")
                .phoneNumber("0912345678")
                .build();
    }

    @Test
    @DisplayName("Tax code format invalid (e.g. less than 10 digits) -> REJECTED, handled by AI")
    void validateAndProcess_WhenTaxCodeFormatInvalid_ShouldRejectWithAiHandled() {
        testRequest.setTaxCode("123"); // invalid

        boolean result = trialPreValidationService.validateAndProcess(testRequest);

        assertThat(result).isFalse();
        assertThat(testRequest.getStatus()).isEqualTo(TrialRequestStatus.REJECTED);
        assertThat(testRequest.getAiPrecheckStatus()).isEqualTo(AiPrecheckStatus.FAILED);
        assertThat(testRequest.getHandledByName()).isEqualTo("AI tự xử lý");
        assertThat(testRequest.getAiPrecheckReason()).contains("Mã số thuế không đúng cấu trúc");
        verify(trialAccountRequestRepository).save(testRequest);
        verify(brevoClient).sendEmail(eq("test@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("Tax code already registered in company -> REJECTED, handled by AI")
    void validateAndProcess_WhenTaxCodeAlreadyInCompany_ShouldRejectWithAiHandled() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(true);

        boolean result = trialPreValidationService.validateAndProcess(testRequest);

        assertThat(result).isFalse();
        assertThat(testRequest.getStatus()).isEqualTo(TrialRequestStatus.REJECTED);
        assertThat(testRequest.getAiPrecheckStatus()).isEqualTo(AiPrecheckStatus.FAILED);
        assertThat(testRequest.getHandledByName()).isEqualTo("AI tự xử lý");
        assertThat(testRequest.getAiPrecheckReason()).contains("đã hoạt động trên hệ thống");
        verify(trialAccountRequestRepository).save(testRequest);
        verify(brevoClient).sendEmail(eq("test@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("Tax code already exists in pending/approved trial requests -> REJECTED, handled by AI")
    void validateAndProcess_WhenTaxCodeAlreadyInTrialRequest_ShouldRejectWithAiHandled() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(false);
        when(trialAccountRequestRepository.existsByTaxCodeAndStatusIn(eq("0123456789"), anyList())).thenReturn(true);

        boolean result = trialPreValidationService.validateAndProcess(testRequest);

        assertThat(result).isFalse();
        assertThat(testRequest.getStatus()).isEqualTo(TrialRequestStatus.REJECTED);
        assertThat(testRequest.getAiPrecheckStatus()).isEqualTo(AiPrecheckStatus.FAILED);
        assertThat(testRequest.getHandledByName()).isEqualTo("AI tự xử lý");
        verify(trialAccountRequestRepository).save(testRequest);
        verify(brevoClient).sendEmail(eq("test@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("Email already exists in users table -> REJECTED, handled by AI")
    void validateAndProcess_WhenEmailAlreadyInUsers_ShouldRejectWithAiHandled() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(false);
        when(trialAccountRequestRepository.existsByTaxCodeAndStatusIn(eq("0123456789"), anyList())).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        boolean result = trialPreValidationService.validateAndProcess(testRequest);

        assertThat(result).isFalse();
        assertThat(testRequest.getStatus()).isEqualTo(TrialRequestStatus.REJECTED);
        assertThat(testRequest.getAiPrecheckStatus()).isEqualTo(AiPrecheckStatus.FAILED);
        assertThat(testRequest.getHandledByName()).isEqualTo("AI tự xử lý");
        assertThat(testRequest.getAiPrecheckReason()).contains("đã được sử dụng cho một tài khoản");
        verify(trialAccountRequestRepository).save(testRequest);
        verify(brevoClient).sendEmail(eq("test@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("All valid -> PASSED, PENDING for System Manager review")
    void validateAndProcess_WhenAllValid_ShouldPassAndSetPending() {
        when(companyRepository.existsByTaxCode("0123456789")).thenReturn(false);
        when(trialAccountRequestRepository.existsByTaxCodeAndStatusIn(eq("0123456789"), anyList())).thenReturn(false);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(trialAccountRequestRepository.existsByEmailAndStatusIn(eq("test@example.com"), anyList())).thenReturn(false);

        boolean result = trialPreValidationService.validateAndProcess(testRequest);

        assertThat(result).isTrue();
        assertThat(testRequest.getStatus()).isEqualTo(TrialRequestStatus.PENDING);
        assertThat(testRequest.getAiPrecheckStatus()).isEqualTo(AiPrecheckStatus.PASSED);
        assertThat(testRequest.getHandledByName()).isNull();
        verify(trialAccountRequestRepository).save(testRequest);
        verify(brevoClient, never()).sendEmail(anyString(), anyString(), anyString());
    }
}

