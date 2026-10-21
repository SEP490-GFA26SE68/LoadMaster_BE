package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.request.support.ApproveTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.ContactOfGuest;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.ContactOfGuestRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.support.impl.TrialAccountRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrialAccountRequestServiceTest {

    @Mock
    private TrialAccountRequestRepository trialAccountRequestRepository;

    @Mock
    private ContactOfGuestRepository contactOfGuestRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private CreditService creditService;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private TemporaryPasswordGenerator temporaryPasswordGenerator;

    @Mock
    private BrevoClient brevoClient;

    @InjectMocks
    private TrialAccountRequestServiceImpl trialAccountRequestService;

    private User systemManager;
    private TrialAccountRequest trialRequest;
    private ContactOfGuest contact;
    private SubscriptionPlan proPlan;

    @BeforeEach
    void setUp() {
        systemManager = User.builder()
                .id(99L)
                .email("manager@loadmaster.local")
                .fullName("System Manager")
                .userRoleType(UserRole.SYSTEM_MANAGER)
                .build();

        proPlan = SubscriptionPlan.builder()
                .id(2L)
                .planCode("PRO_MONTHLY")
                .name("Gói Chuyên Nghiệp (Pro)")
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(10000)
                .active(true)
                .build();

        contact = ContactOfGuest.builder()
                .id(10L)
                .fullName("Nguyen Van Khach")
                .email("khach@logistics.vn")
                .phoneNumber("0901234567")
                .companyName("Logistics Khach VN")
                .companyCode("COMP-KHACH")
                .taxCode("0109988776")
                .requestType(ContactRequestType.DEMO_REQUEST)
                .status(ContactStatus.IN_PROGRESS)
                .createdAt(Instant.now())
                .build();

        trialRequest = TrialAccountRequest.builder()
                .id(1L)
                .contact(contact)
                .fullName("Nguyen Van Khach")
                .email("khach@logistics.vn")
                .phoneNumber("0901234567")
                .companyName("Logistics Khach VN")
                .companyCode("COMP-KHACH")
                .taxCode("0109988776")
                .interestedPlan(proPlan)
                .status(TrialRequestStatus.PENDING)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Approve trial request: creates Company, Keycloak & Postgres User with COMPANY_ADMIN, active subscription and credits, and sends email")
    void approveTrialRequest_success() {
        ApproveTrialAccountRequest request = ApproveTrialAccountRequest.builder()
                .companyName("Logistics Khach VN Updated")
                .companyCode("COMP-KHACH")
                .taxCode("0109988776")
                .planId(2L)
                .trialDays(7)
                .trialCredits(1000)
                .note("Phê duyệt 7 ngày dùng thử gói Pro")
                .build();

        when(trialAccountRequestRepository.findById(1L)).thenReturn(Optional.of(trialRequest));
        when(userRepository.existsByEmail("khach@logistics.vn")).thenReturn(false);
        when(companyRepository.existsByCompanyCode("COMP-KHACH")).thenReturn(false);
        when(companyRepository.existsByTaxCode("0109988776")).thenReturn(false);

        Company savedCompany = Company.builder()
                .id(50L)
                .companyCode("COMP-KHACH")
                .companyName("Logistics Khach VN Updated")
                .taxCode("0109988776")
                .billingEmail("khach@logistics.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        when(companyRepository.save(any(Company.class))).thenReturn(savedCompany);

        when(temporaryPasswordGenerator.generate()).thenReturn("TempSecure#123");
        when(keycloakAdminClient.createUser("khach@logistics.vn", "Nguyen Van Khach")).thenReturn("kc-user-50");

        User savedUser = User.builder()
                .id(101L)
                .keycloakId("kc-user-50")
                .company(savedCompany)
                .email("khach@logistics.vn")
                .fullName("Nguyen Van Khach")
                .phoneNumber("0901234567")
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.save(any(CompanySubscription.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trialAccountRequestRepository.save(any(TrialAccountRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        TrialAccountRequestResponse response = trialAccountRequestService.approveTrialRequest(1L, request, systemManager);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TrialRequestStatus.APPROVED);
        assertThat(response.getApprovedById()).isEqualTo(99L);
        assertThat(trialRequest.getStatus()).isEqualTo(TrialRequestStatus.APPROVED);
        assertThat(contact.getStatus()).isEqualTo(ContactStatus.RESOLVED);

        // Verify Keycloak user creation with role ADMIN
        verify(keycloakAdminClient).createUser("khach@logistics.vn", "Nguyen Van Khach");
        verify(keycloakAdminClient).setPassword("kc-user-50", "TempSecure#123");
        verify(keycloakAdminClient).assignRealmRole("kc-user-50", UserRole.ADMIN.name());

        // Verify Postgres user creation with ADMIN
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User createdUser = userCaptor.getValue();
        assertThat(createdUser.getEmail()).isEqualTo("khach@logistics.vn");
        assertThat(createdUser.getUserRoleType()).isEqualTo(UserRole.ADMIN);
        assertThat(createdUser.getCompany().getId()).isEqualTo(50L);

        // Verify Subscription creation with trial duration (7 days)
        ArgumentCaptor<CompanySubscription> subCaptor = ArgumentCaptor.forClass(CompanySubscription.class);
        verify(companySubscriptionRepository).save(subCaptor.capture());
        CompanySubscription createdSub = subCaptor.getValue();
        assertThat(createdSub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(createdSub.getAutoRenew()).isFalse();
        assertThat(createdSub.getPlan().getId()).isEqualTo(2L);
        long daysDiff = ChronoUnit.DAYS.between(createdSub.getStartedAt(), createdSub.getExpiresAt());
        assertThat(daysDiff).isEqualTo(7);

        // Verify Credits granted
        verify(creditService).grantMonthlyCredits(50L, 1000);
        // Verify Email sent with credentials
        ArgumentCaptor<String> emailBodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(brevoClient).sendEmail(eq("khach@logistics.vn"), anyString(), emailBodyCaptor.capture());
        String emailBody = emailBodyCaptor.getValue();
        assertThat(emailBody).contains("TempSecure#123");
        assertThat(emailBody).contains("khach@logistics.vn");
        assertThat(emailBody).contains("COMP-KHACH");
        assertThat(emailBody).contains("Company Admin");
    }

    @Test
    @DisplayName("Approve trial request fails when email is already registered in the system")
    void approveTrialRequest_emailAlreadyExists_throwsException() {
        ApproveTrialAccountRequest request = ApproveTrialAccountRequest.builder().build();

        when(trialAccountRequestRepository.findById(1L)).thenReturn(Optional.of(trialRequest));
        when(userRepository.existsByEmail("khach@logistics.vn")).thenReturn(true);

        assertThatThrownBy(() -> trialAccountRequestService.approveTrialRequest(1L, request, systemManager))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_EXISTS);

        verify(keycloakAdminClient, never()).createUser(anyString(), anyString());
        verify(companyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Approve trial request reuses existing Keycloak user when email exists in Keycloak but not in DB")
    void approveTrialRequest_keycloakUserAlreadyExists_reusesOrphanedUserAndSucceeds() {
        ApproveTrialAccountRequest request = ApproveTrialAccountRequest.builder()
                .companyName("Logistics Reused")
                .taxCode("0109999999")
                .planId(2L)
                .trialDays(14)
                .build();

        when(trialAccountRequestRepository.findById(1L)).thenReturn(Optional.of(trialRequest));
        when(userRepository.existsByEmail("khach@logistics.vn")).thenReturn(false);
        when(keycloakAdminClient.findUserIdByEmail("khach@logistics.vn")).thenReturn("kc-existing-50");
        when(userRepository.findByKeycloakId("kc-existing-50")).thenReturn(Optional.empty());

        when(companyRepository.existsByCompanyCode(anyString())).thenReturn(false);
        when(companyRepository.existsByTaxCode("0109999999")).thenReturn(false);

        Company savedCompany = Company.builder()
                .id(60L)
                .companyCode("COMP-KHACH")
                .companyName("Logistics Reused")
                .taxCode("0109999999")
                .billingEmail("khach@logistics.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        when(companyRepository.save(any(Company.class))).thenReturn(savedCompany);

        when(temporaryPasswordGenerator.generate()).thenReturn("TempSecure#123");
        when(subscriptionPlanRepository.findById(2L)).thenReturn(Optional.of(proPlan));
        when(companySubscriptionRepository.save(any(CompanySubscription.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trialAccountRequestRepository.save(any(TrialAccountRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        TrialAccountRequestResponse response = trialAccountRequestService.approveTrialRequest(1L, request, systemManager);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(TrialRequestStatus.APPROVED);

        // Verify keycloak createUser was NOT called (reused existing)
        verify(keycloakAdminClient, never()).createUser(anyString(), anyString());
        // Password and role must still be set
        verify(keycloakAdminClient).setPassword("kc-existing-50", "TempSecure#123");
        verify(keycloakAdminClient).assignRealmRole("kc-existing-50", UserRole.ADMIN.name());

        // Verify DB user saved with the existing keycloak ID
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getKeycloakId()).isEqualTo("kc-existing-50");
    }

    @Test
    @DisplayName("Reject trial request updates status to REJECTED and sends refusal email")
    void rejectTrialRequest_success_sendsEmailWithReason() {
        when(trialAccountRequestRepository.findById(1L)).thenReturn(Optional.of(trialRequest));
        when(trialAccountRequestRepository.save(any(TrialAccountRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        TrialAccountRequestResponse response = trialAccountRequestService.rejectTrialRequest(
                1L, "Thông tin mã số thuế không thể xác minh trên cổng thông tin quốc gia.", systemManager);

        assertThat(response.getStatus()).isEqualTo(TrialRequestStatus.REJECTED);
        assertThat(trialRequest.getStatus()).isEqualTo(TrialRequestStatus.REJECTED);
        assertThat(contact.getStatus()).isEqualTo(ContactStatus.CLOSED);

        // Verify notification email sent
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        verify(brevoClient).sendEmail(eq("khach@logistics.vn"), anyString(), emailCaptor.capture());
        assertThat(emailCaptor.getValue()).contains("Thông tin mã số thuế không thể xác minh");
    }
}
