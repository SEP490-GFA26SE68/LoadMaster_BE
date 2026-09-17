package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.constant.OptimizationAlgorithm;
import fu.se184491.loadmaster_be.constant.OptimizationJobStatus;
import fu.se184491.loadmaster_be.constant.OptimizationObjective;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "optimization_jobs")
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
}
