package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.response.audit.AuditLogResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.driver.UnloadConfirmationRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.DeviationRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import fu.se184491.loadmaster_be.repository.warehouse.PlacementConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final TripRepository tripRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;
    private final PlacementConfirmationRepository placementConfirmationRepository;
    private final DeviationRepository deviationRepository;
    private final LoadingExecutionRepository loadingExecutionRepository;
    private final UnloadConfirmationRepository unloadConfirmationRepository;

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

    /** Các bản ghi theo đúng thứ tự, kèm tên công ty và mã chuyến như một trang nhật ký. */
    @Transactional(readOnly = true)
    public List<AuditLogResponse> responsesOf(List<AuditLog> logs) {
        if (logs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> companyNames = companyRepository
                .findAllById(logs.stream()
                        .map(AuditLogQueryService::companyOf)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Company::getId, Company::getCompanyName, (a, b) -> a));
        Map<AuditLog, String> tripCodes = tripCodesOf(logs);
        return logs.stream().map(log -> toResponse(log, companyNames::get, tripCodes.get(log))).toList();
    }

    /** Bản ghi đang trỏ tới đâu trên đường lần về chuyến. */
    enum RefKind { TRIP, STOP, PLAN, PLACEMENT, EXECUTION, CONFIRMATION, DEVIATION, UNLOAD }

    record Ref(RefKind kind, Long id) {
        static Ref of(RefKind kind, Long id) {
            return id == null ? null : new Ref(kind, id);
        }
    }

    /**
     * Điểm xuất phát để lần về chuyến: chính đối tượng của bản ghi, hoặc mã ghi trong giá trị mới /
     * cũ ({@code tripId}, {@code placementId}, {@code stopId}, {@code loadPlanId}; gần chuyến nhất
     * trước). Null khi bản ghi không gắn với chuyến nào.
     */
    static Ref startOf(AuditLog log) {
        Long entityId = parseId(log.getEntityId());
        String entityName = log.getEntityName();
        if ("Trip".equals(entityName)) {
            return Ref.of(RefKind.TRIP, entityId);
        }
        Long tripId = valueIn(log, "tripId");
        if (tripId != null) {
            return Ref.of(RefKind.TRIP, tripId);
        }
        if (entityName != null && entityId != null) {
            switch (entityName) {
                case "DeliveryStop": return Ref.of(RefKind.STOP, entityId);
                case "LoadPlan", "LOAD_PLAN": return Ref.of(RefKind.PLAN, entityId);
                case "PackagePlacement": return Ref.of(RefKind.PLACEMENT, entityId);
                default: break;
            }
        }
        Long placementId = valueIn(log, "placementId");
        if (placementId != null) {
            return Ref.of(RefKind.PLACEMENT, placementId);
        }
        Long stopId = valueIn(log, "stopId");
        if (stopId != null) {
            return Ref.of(RefKind.STOP, stopId);
        }
        Long planId = valueIn(log, "loadPlanId");
        if (planId != null) {
            return Ref.of(RefKind.PLAN, planId);
        }
        if (entityName == null || entityId == null) {
            return null;
        }
        return switch (entityName) {
            case "PlacementConfirmation" -> Ref.of(RefKind.CONFIRMATION, entityId);
            case "Deviation" -> Ref.of(RefKind.DEVIATION, entityId);
            case "LoadingExecution" -> Ref.of(RefKind.EXECUTION, entityId);
            case "UnloadConfirmation" -> Ref.of(RefKind.UNLOAD, entityId);
            default -> null;
        };
    }

    /**
     * Mã chuyến của từng bản ghi, tính lúc đọc. Mỗi bản ghi đi từ điểm xuất phát ({@link #startOf})
     * qua xác nhận / sai lệch → placement → phương án → chuyến, phiên xếp → phương án, xác nhận dỡ →
     * điểm giao → chuyến. Mỗi bậc một truy vấn cho cả trang.
     */
    private Map<AuditLog, String> tripCodesOf(List<AuditLog> logs) {
        Map<AuditLog, String> result = new IdentityHashMap<>();
        tripsOf(logs).forEach((log, trip) -> {
            if (trip.getTripCode() != null) {
                result.put(log, trip.getTripCode());
            }
        });
        return result;
    }

    /**
     * Chuyến mà từng bản ghi nói tới (cùng đường lần như {@link #tripCodesOf}); bản ghi không gắn chuyến nào thì
     * không có trong kết quả. Dùng chung cho nhật ký và chuông thông báo.
     */
    @Transactional(readOnly = true)
    public Map<AuditLog, Trip> tripsOf(List<AuditLog> logs) {
        Map<AuditLog, Ref> refs = new IdentityHashMap<>();
        for (AuditLog log : logs) {
            Ref start = startOf(log);
            if (start != null) {
                refs.put(log, start);
            }
        }

        advance(refs, RefKind.CONFIRMATION, placementConfirmationRepository::findAllById,
                c -> c.getId(), c -> Ref.of(RefKind.PLACEMENT, c.getPlacement() == null ? null : c.getPlacement().getId()));
        advance(refs, RefKind.DEVIATION, deviationRepository::findAllById,
                d -> d.getId(), d -> Ref.of(RefKind.PLACEMENT, d.getPlacement() == null ? null : d.getPlacement().getId()));
        advance(refs, RefKind.UNLOAD, unloadConfirmationRepository::findAllById,
                u -> u.getId(), u -> Ref.of(RefKind.STOP, u.getDeliveryStop() == null ? null : u.getDeliveryStop().getId()));
        advance(refs, RefKind.EXECUTION, loadingExecutionRepository::findAllById,
                e -> e.getId(), e -> Ref.of(RefKind.PLAN, e.getLoadPlan() == null ? null : e.getLoadPlan().getId()));
        advance(refs, RefKind.PLACEMENT, packagePlacementRepository::findAllById,
                p -> p.getId(), p -> Ref.of(RefKind.PLAN, p.getLoadPlan() == null ? null : p.getLoadPlan().getId()));
        advance(refs, RefKind.PLAN, loadPlanRepository::findAllById,
                LoadPlan::getId, plan -> Ref.of(RefKind.TRIP, plan.getJob() == null || plan.getJob().getTrip() == null
                        ? null : plan.getJob().getTrip().getId()));
        advance(refs, RefKind.STOP, deliveryStopRepository::findAllById,
                s -> s.getId(), s -> Ref.of(RefKind.TRIP, s.getTrip() == null ? null : s.getTrip().getId()));
        if (refs.isEmpty()) {
            return Map.of();
        }

        Map<Long, Trip> tripById = new HashMap<>();
        for (Trip trip : tripRepository.findAllById(idsOf(refs, RefKind.TRIP))) {
            tripById.put(trip.getId(), trip);
        }
        Map<AuditLog, Trip> result = new IdentityHashMap<>();
        refs.forEach((log, ref) -> {
            Trip trip = ref.kind() == RefKind.TRIP ? tripById.get(ref.id()) : null;
            if (trip != null) {
                result.put(log, trip);
            }
        });
        return result;
    }

    /**
     * Bản ghi mới nhất của các loại việc cho trước, từ {@code since}, tối đa {@code max} dòng.
     * {@code companyId} null là mọi công ty (quản trị hệ thống).
     */
    @Transactional(readOnly = true)
    public List<AuditLog> recent(Long companyId, Set<String> actions, Instant since, int max) {
        if (actions.isEmpty()) {
            return List.of();
        }
        Pageable pageable = PageRequest.of(0, max, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return auditLogRepository.findAll(AuditLogSpecifications.recent(companyId, actions, since), pageable).getContent();
    }

    /** Đưa mọi bản ghi đang ở bậc {@code kind} lên bậc kế tiếp bằng một truy vấn; lần không ra thì bỏ. */
    private static <E> void advance(Map<AuditLog, Ref> refs, RefKind kind,
                                    Function<Set<Long>, List<E>> load,
                                    Function<E, Long> idOf, Function<E, Ref> next) {
        Set<Long> ids = idsOf(refs, kind);
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Ref> nextById = new HashMap<>();
        for (E entity : load.apply(ids)) {
            Ref ref = next.apply(entity);
            if (ref != null) {
                nextById.put(idOf.apply(entity), ref);
            }
        }
        refs.entrySet().removeIf(entry -> {
            if (entry.getValue().kind() != kind) {
                return false;
            }
            Ref ref = nextById.get(entry.getValue().id());
            if (ref == null) {
                return true;
            }
            entry.setValue(ref);
            return false;
        });
    }

    private static Set<Long> idsOf(Map<AuditLog, Ref> refs, RefKind kind) {
        Set<Long> ids = new HashSet<>();
        refs.values().forEach(ref -> {
            if (ref.kind() == kind) {
                ids.add(ref.id());
            }
        });
        return ids;
    }

    private static Long valueIn(AuditLog log, String key) {
        Long value = idIn(log.getNewValues(), key);
        return value != null ? value : idIn(log.getOldValues(), key);
    }

    static Long idIn(Map<String, Object> values, String key) {
        if (values == null) {
            return null;
        }
        Object raw = values.get(key);
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
