package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ApproveTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.request.support.CreateTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import org.springframework.data.domain.Pageable;

public interface TrialAccountRequestService {

    TrialAccountRequestResponse createTrialRequest(CreateTrialAccountRequest request, User requestedBy);

    PageResponse<TrialAccountRequestResponse> getTrialRequests(String search, TrialRequestStatus status, Pageable pageable);

    TrialAccountRequestResponse getTrialRequestById(Long id);

    TrialAccountRequestResponse approveTrialRequest(Long id, ApproveTrialAccountRequest request, User approvedBy);

    TrialAccountRequestResponse rejectTrialRequest(Long id, String reason, User rejectedBy);
}
