package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.StopZoneResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripDetailResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.PackageFactsCalculator;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phương án xếp hàng 3D (kỹ năng C): số của một phương án, dữ kiện của một kiện, lọc kiện, so ba phương án của lần chạy
 * mới nhất. Đọc qua {@link LoadPlanQueryService} (lọc theo công ty của phiên: phương án công ty khác là
 * {@code LOAD_PLAN_NOT_FOUND}). Mọi dữ kiện vị trí do {@link PackageFactsCalculator} tính; mô hình không tự suy. Số theo
 * xe hiện tại của chuyến (giới hạn trục, khoảng cách tới cửa) bị bỏ khi phương án lỗi thời, vì xe có thể đã đổi.
 */
@Component
@RequiredArgsConstructor
public class LoadPlanTools implements AssistantTool {

    /** Mã lỗi riêng của công cụ (không thuộc {@link ErrorCode}): kiện không có trong phương án. */
    public static final String PACKAGE_NOT_IN_PLAN = "PACKAGE_NOT_IN_PLAN";
    /** Phương án không dựng lại được vùng điểm giao nên không biết kiện nào nằm ngoài vùng. */
    public static final String ZONES_NOT_AVAILABLE = "ZONES_NOT_AVAILABLE";
    /** Mã mẫu của mỗi lý do chưa xếp trong {@code get_load_plan}; đủ danh sách thì gọi {@code find_packages}. */
    static final int REASON_SAMPLES = 5;

    private final CurrentUserService currentUser;
    private final TripService trips;
    private final TripLookupHelper lookup;
    private final LoadPlanQueryService loadPlans;

    public enum PackageFilter { HEAVIEST, OUT_OF_ZONE, BY_STOP, UNPLACED, PINNED }

    /** Số của một phương án. Trường vắng là không có số (không phải 0). Phần trăm là 0…100. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanSummary(Long loadPlanId, String tripCode, String label, String objective, String planName,
                              boolean approved, String approvedBy, boolean manuallyEdited, boolean stale,
                              Double volumePercent, Double weightPercent, Double frontAxleLoadKg,
                              Double rearAxleLoadKg, Double frontAxleLimitKg, Double rearAxleLimitKg,
                              Integer rehandlingCount, int placedCount, int unplacedCount, int pinnedCount,
                              List<ReasonGroup> unplacedByReason) {
    }

    /** Kiện chưa xếp cùng một lý do; {@code reason} là mã thô dịch vụ tối ưu ghi, có thể ngoài enum của Java. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReasonGroup(String reason, int count, List<String> samplePackageCodes) {
    }

    /** Dữ kiện tính được của một kiện. Kiện chưa xếp chỉ có {@code reason} và số đo của kiện. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PackageFacts(String packageCode, boolean placed, String reason, Integer stopSequence,
                               String stopName, Integer totalStops, Integer zoneOfStopSequence, Boolean outOfZone,
                               Double fromFrontWallCm, Double distanceToDoorCm, Boolean onFloor, List<String> restsOn,
                               List<String> restingOnIt, Double weightKg, Double lengthCm, Double widthCm,
                               Double heightCm, Integer loadingStep, Integer totalSteps, Integer unloadRank,
                               Integer unloadTotal, Boolean pinned) {
    }

    /** Một dòng của {@code find_packages}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PackageRow(String packageCode, Double weightKg, Integer stopSequence, String stopName,
                             Integer loadingStep, Boolean outOfZone, Boolean pinned, String reason, Double lengthCm,
                             Double widthCm, Double heightCm) {
    }

    /** Ba phương án (cộng bản duyệt, bản chỉnh tay) của lần chạy tối ưu mới nhất của chuyến. */
    public record PlanComparison(String tripCode, int runCount, List<PlanSummary> plans) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DISPATCHER, UserRole.MANAGER);
    }

    @Tool(name = "get_load_plan", description = """
            Số của một phương án xếp hàng 3D theo mã phương án (loadPlanId, số): nhãn A/B/C, mục tiêu (MAX_VOLUME \
            tối đa thể tích, AXLE_BALANCE cân bằng tải trục, MIN_REHANDLING ít dỡ-xếp lại), % thể tích và % tải \
            trọng, tải trục trước/sau và giới hạn của xe (chỉ khi xe khai), số lần dỡ-xếp lại, số kiện đã xếp / chưa \
            xếp / đã ghim, kiện chưa xếp gom theo lý do (mã thô). Trường vắng là hệ thống không có số đó; phương án \
            lỗi thời thì không có số theo xe hiện tại.""")
    public PlanSummary getLoadPlan(@ToolParam(description = "Mã phương án (số), ví dụ mã phương án đang xem")
                                   Long loadPlanId) {
        LoadPlanDetailResponse plan = plan(loadPlanId);
        TripDetailResponse trip = trips.getTripDetail(plan.getTripId());
        return summaryOf(plan, trip.trip() == null ? null : trip.trip().tripCode(), vehicleOf(plan), true);
    }

    @Tool(name = "get_package_facts", description = """
            Dữ kiện tính bằng code của một kiện trong một phương án: điểm giao thứ mấy / tổng số điểm, vùng điểm giao \
            chứa kiện và có nằm ngoài vùng của điểm mình không, cách vách trước và cách cửa sau bao nhiêu cm, nằm \
            trên sàn hay tựa lên kiện nào, kiện nào tựa lên nó, khối lượng, kích thước khi xếp, bước xếp (loadingStep, \
            dùng cho set_step), thứ tự dỡ, đã ghim chưa. Kiện chưa xếp: lý do (mã thô). Lỗi PACKAGE_NOT_IN_PLAN: \
            kiện không có trong phương án này.""")
    public Object getPackageFacts(
            @ToolParam(description = "Mã phương án (số)") Long loadPlanId,
            @ToolParam(description = "Mã kiện như trên nhãn, ví dụ mã kiện đang chọn") String packageCode) {
        LoadPlanDetailResponse plan = plan(loadPlanId);
        Optional<PlacementDetailResponse> placed = placements(plan).stream()
                .filter(placement -> matches(packageCode, placement.getPackageCode(), placement.getTrackingBarcode()))
                .findFirst();
        if (placed.isEmpty()) {
            return unplaced(plan).stream()
                    .filter(item -> matches(packageCode, item.getPackageCode(), item.getTrackingBarcode()))
                    .findFirst()
                    .<Object>map(LoadPlanTools::factsOf)
                    .orElseGet(() -> ToolResults.error(PACKAGE_NOT_IN_PLAN));
        }
        TripDetailResponse trip = trips.getTripDetail(plan.getTripId());
        return factsOf(plan, placed.get(), trip, vehicleOf(plan));
    }

    @Tool(name = "find_packages", description = """
            Lọc kiện của một phương án, tối đa 20 dòng kèm tổng số (total): HEAVIEST kiện đã xếp nặng nhất trước; \
            OUT_OF_ZONE kiện nằm ngoài vùng điểm giao của mình (lỗi ZONES_NOT_AVAILABLE: phương án không có vùng); \
            BY_STOP kiện của một điểm giao theo thứ tự dỡ (cần stopSequence); UNPLACED kiện chưa xếp kèm lý do; \
            PINNED kiện đã ghim.""")
    public Object findPackages(
            @ToolParam(description = "Mã phương án (số)") Long loadPlanId,
            @ToolParam(description = "HEAVIEST, OUT_OF_ZONE, BY_STOP, UNPLACED hoặc PINNED") PackageFilter filter,
            @ToolParam(required = false, description = "Thứ tự điểm giao (1 là điểm đầu), chỉ dùng với BY_STOP")
            Integer stopSequence) {
        if (filter == null) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }
        LoadPlanDetailResponse plan = plan(loadPlanId);
        List<PlacementDetailResponse> placed = placements(plan);
        List<PackageRow> rows = switch (filter) {
            case HEAVIEST -> placed.stream()
                    .sorted(Comparator.comparing(PlacementDetailResponse::getWeightKg,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(placement -> rowOf(placement, null)).toList();
            case OUT_OF_ZONE -> {
                if (placed.stream().allMatch(placement -> PackageFactsCalculator.outOfZone(placement) == null)) {
                    yield null;
                }
                yield placed.stream().filter(placement -> Boolean.TRUE.equals(PackageFactsCalculator.outOfZone(placement)))
                        .map(placement -> rowOf(placement, true)).toList();
            }
            case BY_STOP -> {
                if (stopSequence == null) {
                    throw new AppException(ErrorCode.INVALID_INPUT);
                }
                yield PackageFactsCalculator.unloadOrder(placed).stream()
                        .filter(placement -> stopSequence.equals(placement.getStopSequence()))
                        .map(placement -> rowOf(placement, null)).toList();
            }
            case UNPLACED -> unplaced(plan).stream().map(LoadPlanTools::rowOf).toList();
            case PINNED -> placed.stream().filter(placement -> Boolean.TRUE.equals(placement.getPinned()))
                    .map(placement -> rowOf(placement, null)).toList();
        };
        return rows == null ? ToolResults.error(ZONES_NOT_AVAILABLE) : ToolResults.page(rows, ToolResults.MAX_ROWS);
    }

    @Tool(name = "compare_plans", description = """
            So các phương án của lần chạy tối ưu mới nhất của một chuyến (thường là A, B, C; có thể thêm bản duyệt \
            hay bản chỉnh tay cùng lần chạy): mỗi phương án kèm loadPlanId và các số như get_load_plan (không có \
            kiện mẫu). runCount là số lần chạy của chuyến.""")
    public PlanComparison comparePlans(@ToolParam(description = "Mã chuyến, ví dụ TRIP-2026-0914") String tripCode) {
        if (!StringUtils.hasText(tripCode)) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        Trip trip = lookup.requireTrip(tripCode, companyOf(currentUser.getCurrentUser()));
        List<LoadPlanDetailResponse> all = loadPlans.getTripLoadPlans(trip.getId()).getPlans();
        List<LoadPlanDetailResponse> plans = all == null ? List.of() : all;
        Long latestJob = plans.stream().map(LoadPlanDetailResponse::getJobId).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        int runCount = (int) plans.stream().map(LoadPlanDetailResponse::getJobId).filter(Objects::nonNull)
                .distinct().count();
        Optional<TripVehicleConfigResponse> vehicle = plans.isEmpty() ? Optional.empty() : vehicleOf(trip.getId());
        List<PlanSummary> latest = plans.stream()
                .filter(plan -> latestJob != null && latestJob.equals(plan.getJobId()))
                .sorted(Comparator.comparing(LoadPlanDetailResponse::getCandidateLabel,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LoadPlanDetailResponse::getId))
                .map(plan -> summaryOf(plan, trip.getTripCode(), vehicle, false))
                .toList();
        return new PlanComparison(trip.getTripCode(), runCount, latest);
    }

    private LoadPlanDetailResponse plan(Long loadPlanId) {
        if (loadPlanId == null) {
            throw new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND);
        }
        return loadPlans.getPlan(loadPlanId);
    }

    /** Xe hiện tại của chuyến; chuyến chưa gắn xe hay loại xe thì không có. */
    private Optional<TripVehicleConfigResponse> vehicleOf(LoadPlanDetailResponse plan) {
        return plan.getTripId() == null ? Optional.empty() : vehicleOf(plan.getTripId());
    }

    private Optional<TripVehicleConfigResponse> vehicleOf(Long tripId) {
        try {
            return Optional.ofNullable(trips.getTripVehicleConfig(tripId));
        } catch (AppException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_INPUT) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static PlanSummary summaryOf(LoadPlanDetailResponse plan, String tripCode,
                                         Optional<TripVehicleConfigResponse> vehicle, boolean withSamples) {
        boolean stale = Boolean.TRUE.equals(plan.getStale());
        Optional<TripVehicleConfigResponse> current = stale ? Optional.empty() : vehicle;
        List<PlacementDetailResponse> placed = placements(plan);
        List<UnplacedPackageResponse> unplaced = unplaced(plan);
        int pinned = (int) placed.stream().filter(placement -> Boolean.TRUE.equals(placement.getPinned())).count();
        return new PlanSummary(plan.getId(), tripCode, plan.getCandidateLabel(), plan.getObjective(),
                plan.getPlanName(), Boolean.TRUE.equals(plan.getApproved()), plan.getApprovedByName(),
                Boolean.TRUE.equals(plan.getManuallyEdited()), stale, percent(plan.getVolumeUtilization()),
                percent(plan.getWeightUtilization()), tenth(plan.getFrontAxleLoad()), tenth(plan.getRearAxleLoad()),
                current.map(TripVehicleConfigResponse::frontAxleLimitKg).map(LoadPlanTools::tenth).orElse(null),
                current.map(TripVehicleConfigResponse::rearAxleLimitKg).map(LoadPlanTools::tenth).orElse(null),
                plan.getRehandlingCount(), placed.size(), unplaced.size(), pinned,
                reasonGroups(unplaced, withSamples ? REASON_SAMPLES : 0));
    }

    private static List<ReasonGroup> reasonGroups(List<UnplacedPackageResponse> unplaced, int samples) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (UnplacedPackageResponse item : unplaced) {
            String reason = Objects.requireNonNullElse(item.getReason(), "UNKNOWN");
            List<String> codes = byReason.computeIfAbsent(reason, key -> new ArrayList<>());
            codes.add(codeOf(item.getPackageCode(), item.getTrackingBarcode()));
        }
        List<ReasonGroup> out = new ArrayList<>();
        byReason.forEach((reason, codes) -> out.add(new ReasonGroup(reason, codes.size(),
                samples <= 0 ? null : codes.stream().filter(Objects::nonNull).limit(samples).toList())));
        return out;
    }

    private static PackageFacts factsOf(LoadPlanDetailResponse plan, PlacementDetailResponse target,
                                        TripDetailResponse trip, Optional<TripVehicleConfigResponse> vehicle) {
        List<PlacementDetailResponse> all = placements(plan);
        boolean stale = Boolean.TRUE.equals(plan.getStale());
        Integer innerLength = stale ? null : vehicle.map(TripVehicleConfigResponse::innerLength).orElse(null);
        List<PlacementDetailResponse> unloadOrder = PackageFactsCalculator.unloadOrder(all);
        Integer totalSteps = all.stream().map(PlacementDetailResponse::getStepSequence).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        Integer totalStops = trip == null || trip.stops() == null ? null : trip.stops().size();
        return new PackageFacts(codeOf(target.getPackageCode(), target.getTrackingBarcode()), true, null,
                target.getStopSequence(), target.getStopName(), totalStops, zoneStopSequence(plan, target),
                PackageFactsCalculator.outOfZone(target), tenth(target.getPosX()),
                PackageFactsCalculator.distanceToDoorCm(target, innerLength),
                PackageFactsCalculator.onFloor(target, all), PackageFactsCalculator.restsOn(target, all),
                PackageFactsCalculator.restingOnIt(target, all), tenth(target.getWeightKg()),
                tenth(target.getPackedLength()), tenth(target.getPackedWidth()), tenth(target.getPackedHeight()),
                target.getStepSequence(), totalSteps, unloadOrder.indexOf(target) + 1, unloadOrder.size(),
                Boolean.TRUE.equals(target.getPinned()));
    }

    private static PackageFacts factsOf(UnplacedPackageResponse item) {
        return new PackageFacts(codeOf(item.getPackageCode(), item.getTrackingBarcode()), false,
                Objects.requireNonNullElse(item.getReason(), "UNKNOWN"), null, null, null, null, null, null, null,
                null, null, null, tenth(item.getWeightKg()), tenth(item.getLengthCm()), tenth(item.getWidthCm()),
                tenth(item.getHeightCm()), null, null, null, null, null);
    }

    /** Thứ tự điểm giao của vùng chứa kiện; {@code null} khi phương án không có vùng. */
    private static Integer zoneStopSequence(LoadPlanDetailResponse plan, PlacementDetailResponse target) {
        if (target.getStopZoneId() == null || plan.getStopZones() == null) {
            return null;
        }
        return plan.getStopZones().stream().filter(zone -> target.getStopZoneId().equals(zone.getId()))
                .map(StopZoneResponse::getStopSequence).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static PackageRow rowOf(PlacementDetailResponse placement, Boolean outOfZone) {
        return new PackageRow(codeOf(placement.getPackageCode(), placement.getTrackingBarcode()),
                tenth(placement.getWeightKg()), placement.getStopSequence(), placement.getStopName(),
                placement.getStepSequence(), outOfZone, Boolean.TRUE.equals(placement.getPinned()) ? true : null,
                null, null, null, null);
    }

    private static PackageRow rowOf(UnplacedPackageResponse item) {
        return new PackageRow(codeOf(item.getPackageCode(), item.getTrackingBarcode()), tenth(item.getWeightKg()),
                null, null, null, null, null, Objects.requireNonNullElse(item.getReason(), "UNKNOWN"),
                tenth(item.getLengthCm()), tenth(item.getWidthCm()), tenth(item.getHeightCm()));
    }

    private static List<PlacementDetailResponse> placements(LoadPlanDetailResponse plan) {
        return plan.getPlacements() == null ? List.of() : plan.getPlacements();
    }

    private static List<UnplacedPackageResponse> unplaced(LoadPlanDetailResponse plan) {
        return plan.getUnplaced() == null ? List.of() : plan.getUnplaced();
    }

    private static boolean matches(String asked, String packageCode, String trackingBarcode) {
        if (!StringUtils.hasText(asked)) {
            return false;
        }
        String wanted = asked.trim().toUpperCase(Locale.ROOT);
        return (packageCode != null && packageCode.toUpperCase(Locale.ROOT).equals(wanted))
                || (trackingBarcode != null && trackingBarcode.toUpperCase(Locale.ROOT).equals(wanted));
    }

    private static String codeOf(String packageCode, String trackingBarcode) {
        return packageCode != null ? packageCode : trackingBarcode;
    }

    private static Long companyOf(User user) {
        if (user.getCompany() == null) {
            throw new AppException(ErrorCode.TRIP_NOT_FOUND);
        }
        return user.getCompany().getId();
    }

    /** Tỷ lệ 0…1 của dịch vụ tối ưu thành phần trăm, một chữ số lẻ. */
    private static Double percent(BigDecimal ratio) {
        return ratio == null ? null : Math.round(ratio.doubleValue() * 1000) / 10.0;
    }

    private static Double tenth(BigDecimal value) {
        return value == null ? null : Math.round(value.doubleValue() * 10) / 10.0;
    }
}
