package fu.se184491.loadmaster_be.repository.optimization;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Ghi chỗ xếp của một phương án bằng SQL thuần: cột toạ độ và kích thước là số thập phân (cm) do dịch vụ tối ưu khai,
 * entity JPA {@code PackagePlacement} ánh xạ chúng thành số nguyên nên sẽ cắt phần lẻ.
 */
@Repository
@RequiredArgsConstructor
public class LoadPlanWriteRepository {

    /** Một chỗ xếp mới, cm. */
    public record NewPlacement(Long packageId, BigDecimal posX, BigDecimal posY, BigDecimal posZ,
                               BigDecimal packedLength, BigDecimal packedWidth, BigDecimal packedHeight,
                               Integer rotationType, Integer stepSequence, boolean pinned, Long stopId) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public void insertPlacements(Long planId, List<NewPlacement> placements) {
        if (placements.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = placements.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("planId", planId)
                        .addValue("packageId", p.packageId())
                        .addValue("x", p.posX())
                        .addValue("y", p.posY())
                        .addValue("z", p.posZ())
                        .addValue("l", p.packedLength())
                        .addValue("w", p.packedWidth())
                        .addValue("h", p.packedHeight())
                        .addValue("rotation", p.rotationType())
                        .addValue("step", p.stepSequence())
                        .addValue("pinned", p.pinned())
                        .addValue("stopId", p.stopId()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO package_placements (load_plan_id, package_id, pos_x, pos_y, pos_z,
                    packed_length, packed_width, packed_height, rotation_type, step_sequence, pinned, stop_zone_id)
                VALUES (:planId, :packageId, :x, :y, :z, :l, :w, :h, :rotation, :step, :pinned, :stopId)
                """, rows);
    }

    /**
     * Job còn chờ / đang chạy thành FAILED kèm mã lý do. Chỉ đổi khi job vẫn PENDING hoặc RUNNING:
     * trả 1 cho đúng một lượt đổi, lượt đến sau (hay job vừa xong) trả 0.
     */
    public int failUnfinishedJob(Long jobId, String failureCode) {
        return jdbc.update("""
                UPDATE optimization_jobs SET status = 'FAILED', failure_code = :code
                WHERE id = :id AND status IN ('PENDING', 'RUNNING')
                """, Map.of("id", jobId, "code", failureCode));
    }

    /** Chép kiện không xếp được của phương án nguồn sang phương án mới. */
    public void copyUnplaced(Long sourcePlanId, Long targetPlanId) {
        jdbc.update("""
                INSERT INTO unplaced_packages (load_plan_id, package_id, rejection_reason)
                SELECT :target, package_id, rejection_reason FROM unplaced_packages WHERE load_plan_id = :source
                """, Map.of("source", sourcePlanId, "target", targetPlanId));
    }
}
