package fu.se184491.loadmaster_be.entity;

import fu.se184491.loadmaster_be.constant.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Builder.Default
    @Column(name = "current_jobs_used")
    private Integer currentJobsUsed = 0;

    @Builder.Default
    @Column(name = "auto_renew")
    private Boolean autoRenew = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SubscriptionStatus status;
}
