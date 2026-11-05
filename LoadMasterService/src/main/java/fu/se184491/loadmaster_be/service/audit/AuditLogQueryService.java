package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Đọc nhật ký hoạt động theo phạm vi của người xem ({@link AuditLogScope}). */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    static final int MAX_PAGE_SIZE = 100;

    static final String ENTITY_TRIP = "Trip";
    static final String ENTITY_STOP = "DeliveryStop";
    static final String ENTITY_LOAD_PLAN = "LoadPlan";

    private final AuditLogRepository auditLogRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserService currentUserService;
    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final LoadPlanRepository loadPlanRepository;

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
        Map<AuditLog, String> tripCodes = tripCodesOf(logs.getContent());

        return PageResponse.of("Lấy nhật ký hoạt động thành công",
                logs.map(log -> toResponse(log, companyNames::get, tripCodes.get(log))));
    }

    /**
     * Mã chuyến của từng bản ghi, tính lúc đọc: bản ghi về chuyến lấy chính mã chuyến; về điểm giao,
     * phương án lấy chuyến của chúng; bản ghi khác có {@code tripId} trong giá trị mới / cũ thì lấy
     * chuyến đó. Mỗi bảng một truy vấn cho cả trang.
     */
    private Map<AuditLog, String> tripCodesOf(List<AuditLog> logs) {
        Map<AuditLog, Long> tripIdByLog = new IdentityHashMap<>();
        Map<AuditLog, Long> stopIdByLog = new IdentityHashMap<>();
        Map<AuditLog, Long> planIdByLog = new IdentityHashMap<>();
        for (AuditLog log : logs) {
            Long entityId = parseId(log.getEntityId());
            String entityName = log.getEntityName();
            if (entityId != null && ENTITY_TRIP.equals(entityName)) {
                tripIdByLog.put(log, entityId);
            } else if (entityId != null && ENTITY_STOP.equals(entityName)) {
                stopIdByLog.put(log, entityId);
            } else if (entityId != null && ENTITY_LOAD_PLAN.equals(entityName)) {
                planIdByLog.put(log, entityId);
            } else {
                Long tripId = tripIdIn(log.getNewValues());
                putIfPresent(tripIdByLog, log, tripId != null ? tripId : tripIdIn(log.getOldValues()));
            }
        }

        if (!stopIdByLog.isEmpty()) {
            Map<Long, Long> tripOfStop = new HashMap<>();
            for (DeliveryStop stop : deliveryStopRepository.findAllById(new HashSet<>(stopIdByLog.values()))) {
                if (stop.getTrip() != null) {
                    tripOfStop.put(stop.getId(), stop.getTrip().getId());
                }
            }
            stopIdByLog.forEach((log, stopId) -> putIfPresent(tripIdByLog, log, tripOfStop.get(stopId)));
        }
        if (!planIdByLog.isEmpty()) {
            Map<Long, Long> tripOfPlan = new HashMap<>();
            for (LoadPlan plan : loadPlanRepository.findAllById(new HashSet<>(planIdByLog.values()))) {
                if (plan.getJob() != null && plan.getJob().getTrip() != null) {
                    tripOfPlan.put(plan.getId(), plan.getJob().getTrip().getId());
                }
            }
            planIdByLog.forEach((log, planId) -> putIfPresent(tripIdByLog, log, tripOfPlan.get(planId)));
        }
        if (tripIdByLog.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> codeOfTrip = new HashMap<>();
        for (Trip trip : tripRepository.findAllById(new HashSet<>(tripIdByLog.values()))) {
            if (trip.getTripCode() != null) {
                codeOfTrip.put(trip.getId(), trip.getTripCode());
            }
        }
        Map<AuditLog, String> result = new IdentityHashMap<>();
        tripIdByLog.forEach((log, tripId) -> putIfPresent(result, log, codeOfTrip.get(tripId)));
        return result;
    }

    private static <K, V> void putIfPresent(Map<K, V> map, K key, V value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    static Long tripIdIn(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Object raw = values.get("tripId");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return raw == null ? null : parseId(raw.toString());
    }

    static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
        return toResponse(log, companyName, null);
    }

    static AuditLogResponse toResponse(AuditLog log, Function<Long, String> companyName, String entityCode) {
        User user = log.getUser();
        Long companyId = companyOf(log);
        return AuditLogResponse.builder()
                .id(log.getId())
                .actionType(log.getActionType())
                .entityName(log.getEntityName())
                .entityId(log.getEntityId())
                .entityCode(entityCode)
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
