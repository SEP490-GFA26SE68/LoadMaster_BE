package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.constant.LoadingExecutionStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "loading_executions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LoadingExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "load_plan_id", unique = true)
    private LoadPlan loadPlan;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "worker_id")
    private User worker;

    @Column(name = "seal_number", length = 50)
    private String sealNumber;

    @Builder.Default @PositiveOrZero @Column(name = "total_deviations")
    private Integer totalDeviations = 0;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private LoadingExecutionStatus status;

    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
}
