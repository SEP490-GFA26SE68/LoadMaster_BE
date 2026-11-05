package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Đọc nhật ký hoạt động theo phạm vi của người xem ({@link AuditLogScope}). */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(AuditLogFilter filter, String companyParam, int page, int size) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc");
        }
        User viewer = currentUserService.getCurrentUser();
        AuditLogScope scope = AuditLogScope.of(
                viewer.getUserRoleType(),
                viewer.getCompany() == null ? null : viewer.getCompany().getId(),
                companyParam);

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AuditLog> logs = auditLogRepository.findAll(AuditLogSpecifications.of(scope, filter), pageable);

        Map<Long, String> companyNames = companyRepository
                .findAllById(logs.getContent().stream()
                        .map(AuditLogQueryService::companyOf)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Company::getId, Company::getCompanyName, (a, b) -> a));

        return PageResponse.of("Lấy nhật ký hoạt động thành công",
                logs.map(log -> toResponse(log, companyNames::get)));
    }

    /** Công ty của bản ghi; bản ghi cũ chưa có cột company_id thì lấy công ty của người làm. */
    static Long companyOf(AuditLog log) {
        if (log.getCompanyId() != null) {
            return log.getCompanyId();
        }
        User user = log.getUser();
        return user == null || user.getCompany() == null ? null : user.getCompany().getId();
    }

    static AuditLogResponse toResponse(AuditLog log, Function<Long, String> companyName) {
        User user = log.getUser();
        Long companyId = companyOf(log);
        return AuditLogResponse.builder()
                .id(log.getId())
                .actionType(log.getActionType())
                .entityName(log.getEntityName())
                .entityId(log.getEntityId())
                .userId(user == null ? null : user.getId())
                .userName(user == null ? null : user.getFullName())
                .userRole(user == null || user.getUserRoleType() == null ? null : user.getUserRoleType().name())
                .companyId(companyId)
                .companyName(companyId == null ? null : companyName.apply(companyId))
                .oldValues(log.getOldValues())
                .newValues(log.getNewValues())
                .ipAddress(log.getIpAddress())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
