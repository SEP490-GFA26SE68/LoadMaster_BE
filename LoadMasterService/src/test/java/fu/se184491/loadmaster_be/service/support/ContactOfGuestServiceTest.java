package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.response.support.ContactOfGuestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.support.ContactOfGuest;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.support.ContactOfGuestRepository;
import fu.se184491.loadmaster_be.service.support.impl.ContactOfGuestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactOfGuestServiceTest {

    @Mock
    private ContactOfGuestRepository contactOfGuestRepository;

    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ContactOfGuestServiceImpl contactOfGuestService;

    private SubscriptionPlan plan;
    private User supporter;

    @BeforeEach
    void setUp() {
        plan = SubscriptionPlan.builder()
                .id(1L)
                .name("Gói Pro")
                .build();

        supporter = User.builder()
                .id(2L)
                .fullName("Nguyễn Hỗ Trợ")
                .build();
    }

    @Test
    @DisplayName("Tạo liên hệ thành công: status = NEW, createdAt được gán, plan được map đúng")
    void createContact_Success() {
        CreateContactOfGuestRequest request = CreateContactOfGuestRequest.builder()
                .fullName("Trần Khách")
                .email("khach@gmail.com")
                .phoneNumber("0912345678")
                .companyName("Công ty ABC")
                .interestedPlanId(1L)
                .requestType(ContactRequestType.DEMO_REQUEST)
                .message("Tôi muốn xem demo")
                .build();

        when(subscriptionPlanRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(contactOfGuestRepository.save(any(ContactOfGuest.class))).thenAnswer(invocation -> {
            ContactOfGuest entity = invocation.getArgument(0);
            entity.setId(10L);
            return entity;
        });

        ContactOfGuestResponse response = contactOfGuestService.createContact(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getFullName()).isEqualTo("Trần Khách");
        assertThat(response.getEmail()).isEqualTo("khach@gmail.com");
        assertThat(response.getStatus()).isEqualTo(ContactStatus.NEW);
        assertThat(response.getRequestType()).isEqualTo(ContactRequestType.DEMO_REQUEST);
        assertThat(response.getInterestedPlanName()).isEqualTo("Gói Pro");
        assertThat(response.getCreatedAt()).isNotNull();

        verify(contactOfGuestRepository).save(any(ContactOfGuest.class));
    }

    @Test
    @DisplayName("Xem chi tiết liên hệ thành công")
    void getContactById_Success() {
        ContactOfGuest contact = ContactOfGuest.builder()
                .id(10L)
                .fullName("Trần Khách")
                .email("khach@gmail.com")
                .status(ContactStatus.NEW)
                .requestType(ContactRequestType.PRICING)
                .createdAt(Instant.now())
                .build();

        when(contactOfGuestRepository.findById(10L)).thenReturn(Optional.of(contact));

        ContactOfGuestResponse response = contactOfGuestService.getContactById(10L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getFullName()).isEqualTo("Trần Khách");
    }

    @Test
    @DisplayName("Xem chi tiết liên hệ không tìm thấy: ném CONTACT_REQUEST_NOT_FOUND")
    void getContactById_NotFound() {
        when(contactOfGuestRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contactOfGuestService.getContactById(999L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONTACT_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("Cập nhật liên hệ: chuyển sang RESOLVED tự động gán resolvedAt, gán nhân viên phụ trách")
    void updateContact_StatusResolved_SetsResolvedAt() {
        ContactOfGuest contact = ContactOfGuest.builder()
                .id(10L)
                .fullName("Trần Khách")
                .email("khach@gmail.com")
                .status(ContactStatus.NEW)
                .requestType(ContactRequestType.DEMO_REQUEST)
                .createdAt(Instant.now().minusSeconds(3600))
                .build();

        UpdateContactOfGuestRequest updateRequest = UpdateContactOfGuestRequest.builder()
                .status(ContactStatus.RESOLVED)
                .assignedToId(2L)
                .note("Đã tư vấn demo xong, khách hài lòng")
                .build();

        when(contactOfGuestRepository.findById(10L)).thenReturn(Optional.of(contact));
        when(userRepository.findById(2L)).thenReturn(Optional.of(supporter));
        when(contactOfGuestRepository.save(any(ContactOfGuest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContactOfGuestResponse response = contactOfGuestService.updateContact(10L, updateRequest);

        assertThat(response.getStatus()).isEqualTo(ContactStatus.RESOLVED);
        assertThat(response.getAssignedToName()).isEqualTo("Nguyễn Hỗ Trợ");
        assertThat(response.getNote()).isEqualTo("Đã tư vấn demo xong, khách hài lòng");
        assertThat(response.getResolvedAt()).isNotNull();
    }
}
