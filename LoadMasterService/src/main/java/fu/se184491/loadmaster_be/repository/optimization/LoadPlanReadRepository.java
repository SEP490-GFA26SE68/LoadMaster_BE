package fu.se184491.loadmaster_be.repository.optimization;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Đọc phương án xếp hàng bằng SQL thuần.
 *
 * <p>Các bảng {@code load_plans}, {@code package_placements}, {@code unplaced_packages},
 * {@code optimization_jobs} do dịch vụ tối ưu (Python) ghi: toạ độ là số thập phân, mã thuật
 * toán và mã lý do có giá trị ngoài enum của Java. Đọc qua entity JPA sẽ cắt số hoặc lỗi enum,
 * nên lớp này đọc thẳng cột và giữ chuỗi như đã lưu.</p>
 */
@Repository
@RequiredArgsConstructor
public class LoadPlanReadRepository {

    /** Chủ của một job: chuyến và công ty của chuyến. */
    public record JobOwner(Long jobId, String jobUuid, Long tripId, Long companyId) {
    }

    /** Phương án kèm mã công ty của chuyến. */
    public record PlanWithCompany(LoadPlanDetailResponse plan, Long companyId) {
    }

    public record PlacementRow(Long planId, PlacementDetailResponse placement) {
    }

    public record UnplacedRow(Long planId, UnplacedPackageResponse unplaced) {
    }

    private static final String PLAN_SELECT = """
            SELECT lp.id, lp.job_id, lp.plan_name, lp.version, lp.parent_plan_id,
                   lp.packed_items_count, lp.volume_utilization, lp.weight_utilization,
                   lp.is_approved, lp.approved_by, u.full_name AS approved_by_name,
                   lp.cog_x, lp.cog_y, lp.cog_z, lp.front_axle_load, lp.rear_axle_load,
                   lp.rehandling_count,
                   j.job_uuid, j.trip_id, j.status AS job_status, j.objective,
                   j.algorithm_name, j.computation_ms, t.company_id
            FROM load_plans lp
            JOIN optimization_jobs j ON j.id = lp.job_id
            JOIN trips t ON t.id = j.trip_id
            LEFT JOIN users u ON u.id = lp.approved_by
            """;

    private static final RowMapper<LoadPlanDetailResponse> PLAN_MAPPER = (rs, i) -> mapPlan(rs);

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<JobOwner> findJobOwner(String jobUuid) {
        List<JobOwner> rows = jdbc.query("""
                        SELECT j.id, j.job_uuid, j.trip_id, t.company_id
                        FROM optimization_jobs j
                        JOIN trips t ON t.id = j.trip_id
                        WHERE j.job_uuid = :key
                        """,
                Map.of("key", jobUuid),
                (rs, i) -> new JobOwner(
                        rs.getLong("id"),
                        rs.getString("job_uuid"),
                        rs.getLong("trip_id"),
                        nullableLong(rs, "company_id")));
        return rows.stream().findFirst();
    }

    public Optional<PlanWithCompany> findPlan(Long planId) {
        List<PlanWithCompany> rows = jdbc.query(PLAN_SELECT + " WHERE lp.id = :id",
                Map.of("id", planId),
                (rs, i) -> new PlanWithCompany(mapPlan(rs), nullableLong(rs, "company_id")));
        return rows.stream().findFirst();
    }

    public List<LoadPlanDetailResponse> findPlansByTrip(Long tripId) {
        return jdbc.query(PLAN_SELECT + " WHERE j.trip_id = :tripId ORDER BY lp.id",
                Map.of("tripId", tripId), PLAN_MAPPER);
    }

    public List<LoadPlanDetailResponse> findPlansByJob(Long jobId) {
        return jdbc.query(PLAN_SELECT + " WHERE lp.job_id = :jobId ORDER BY lp.id",
                Map.of("jobId", jobId), PLAN_MAPPER);
    }

    /** Kiện đã xếp của các phương án, theo phương án rồi thứ tự xếp. */
    public List<PlacementRow> findPlacements(Collection<Long> planIds) {
        if (planIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT pp.id, pp.load_plan_id, pp.package_id, p.package_code, p.tracking_barcode,
                               p.actual_weight_kg, pp.pos_x, pp.pos_y, pp.pos_z,
                               pp.packed_length, pp.packed_width, pp.packed_height,
                               pp.rotation_type, pp.step_sequence, pp.pinned,
                               pp.stop_zone_id, ds.stop_name, ds.stop_sequence
                        FROM package_placements pp
                        LEFT JOIN packages p ON p.id = pp.package_id
                        LEFT JOIN delivery_stops ds ON ds.id = pp.stop_zone_id
                        WHERE pp.load_plan_id IN (:ids)
                        ORDER BY pp.load_plan_id, pp.step_sequence, pp.id
                        """,
                Map.of("ids", planIds),
                (rs, i) -> new PlacementRow(rs.getLong("load_plan_id"), PlacementDetailResponse.builder()
                        .id(rs.getLong("id"))
                        .packageId(nullableLong(rs, "package_id"))
                        .packageCode(rs.getString("package_code"))
                        .trackingBarcode(rs.getString("tracking_barcode"))
                        .weightKg(rs.getBigDecimal("actual_weight_kg"))
                        .posX(rs.getBigDecimal("pos_x"))
                        .posY(rs.getBigDecimal("pos_y"))
                        .posZ(rs.getBigDecimal("pos_z"))
                        .packedLength(rs.getBigDecimal("packed_length"))
                        .packedWidth(rs.getBigDecimal("packed_width"))
                        .packedHeight(rs.getBigDecimal("packed_height"))
                        .rotationType(nullableInt(rs, "rotation_type"))
                        .stepSequence(nullableInt(rs, "step_sequence"))
                        .pinned(rs.getBoolean("pinned"))
                        .stopId(nullableLong(rs, "stop_zone_id"))
                        .stopName(rs.getString("stop_name"))
                        .stopSequence(nullableInt(rs, "stop_sequence"))
                        .build()));
    }

    public List<UnplacedRow> findUnplaced(Collection<Long> planIds) {
        if (planIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT up.load_plan_id, up.package_id, p.package_code, p.tracking_barcode,
                               up.rejection_reason
                        FROM unplaced_packages up
                        LEFT JOIN packages p ON p.id = up.package_id
                        WHERE up.load_plan_id IN (:ids)
                        ORDER BY up.load_plan_id, up.id
                        """,
                Map.of("ids", planIds),
                (rs, i) -> new UnplacedRow(rs.getLong("load_plan_id"), UnplacedPackageResponse.builder()
                        .packageId(nullableLong(rs, "package_id"))
                        .packageCode(rs.getString("package_code"))
                        .trackingBarcode(rs.getString("tracking_barcode"))
                        .reason(rs.getString("rejection_reason"))
                        .build()));
    }

    private static LoadPlanDetailResponse mapPlan(ResultSet rs) throws SQLException {
        return LoadPlanDetailResponse.builder()
                .id(rs.getLong("id"))
                .tripId(nullableLong(rs, "trip_id"))
                .jobId(nullableLong(rs, "job_id"))
                .jobUuid(rs.getString("job_uuid"))
                .jobStatus(rs.getString("job_status"))
                .objective(rs.getString("objective"))
                .algorithmName(rs.getString("algorithm_name"))
                .computationMs(nullableLong(rs, "computation_ms"))
                .planName(rs.getString("plan_name"))
                .version(nullableInt(rs, "version"))
                .parentPlanId(nullableLong(rs, "parent_plan_id"))
                .packedItemsCount(nullableInt(rs, "packed_items_count"))
                .volumeUtilization(rs.getBigDecimal("volume_utilization"))
                .weightUtilization(rs.getBigDecimal("weight_utilization"))
                .approved(rs.getBoolean("is_approved"))
                .approvedById(nullableLong(rs, "approved_by"))
                .approvedByName(rs.getString("approved_by_name"))
                .cogX(rs.getBigDecimal("cog_x"))
                .cogY(rs.getBigDecimal("cog_y"))
                .cogZ(rs.getBigDecimal("cog_z"))
                .frontAxleLoad(rs.getBigDecimal("front_axle_load"))
                .rearAxleLoad(rs.getBigDecimal("rear_axle_load"))
                .rehandlingCount(nullableInt(rs, "rehandling_count"))
                .build();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
