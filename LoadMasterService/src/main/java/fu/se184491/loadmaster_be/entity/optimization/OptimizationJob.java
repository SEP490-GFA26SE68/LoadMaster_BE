package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.entity.trip.Trip;


import fu.se184491.loadmaster_be.constant.optimization.OptimizationAlgorithm;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationObjective;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "optimization_jobs", indexes = {
        @Index(name = "idx_optimization_jobs_trip_id", columnList = "trip_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OptimizationJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "trip_id")
    private Trip trip;

    @Column(name = "job_uuid", length = 64, unique = true)
    private String jobUuid;

    @Enumerated(EnumType.STRING) @Column(name = "algorithm_name")
    private OptimizationAlgorithm algorithmName;

    @Enumerated(EnumType.STRING) @Column(name = "objective")
    private OptimizationObjective objective;

    @Builder.Default @Column(name = "time_limit_sec")
    private Integer timeLimitSec = 60;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private OptimizationJobStatus status;

    @Column(name = "computation_ms")
    private Long computationMs;

    @Column(name = "parent_plan_id")
    private Long parentPlanId;

    @Column(name = "seed")
    private Integer seed;

    // Dịch vụ tối ưu (Python) ghi bốn cột này khi tạo job. Java khai để ddl-auto tạo cột (cho phép null).
    @Column(name = "company_id", length = 64)
    private String companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", length = 50)
    private SubscriptionTier subscriptionTier;

    @Column(name = "algorithm_tier", length = 50)
    private String algorithmTier;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    /** Lý do job FAILED khi Java tự kết thúc job (ví dụ JOB_INTERRUPTED: dịch vụ tối ưu dừng giữa chừng). */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    /** Cảnh báo dữ liệu của lần chạy (JSON, do OptimizeService ghi; V55 mặc định []). Java chỉ đọc. */
    @Column(name = "data_warnings", columnDefinition = "jsonb", insertable = false, updatable = false)
    private String dataWarnings;

    @Column(name = "execution_generation")
    private Long executionGeneration;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "dispatch_expires_at")
    private java.time.Instant dispatchExpiresAt;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "lease_expires_at")
    private java.time.Instant leaseExpiresAt;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "execution_started_at")
    private java.time.Instant executionStartedAt;

    /** Job do Java tạo (chạy lại giữ ghim) cũng có giờ tạo, giờ Việt Nam như dịch vụ tối ưu ghi. */
    @PrePersist
    void fillCreatedAt() {
        if (createdAt == null) {
            createdAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }
}
