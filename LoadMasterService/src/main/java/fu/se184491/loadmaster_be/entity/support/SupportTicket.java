package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;


import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_tickets")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupportTicket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "requester_id")
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "job_id")
    private OptimizationJob job;

    @Column(name = "ticket_code", length = 50, unique = true)
    private String ticketCode;

    @Enumerated(EnumType.STRING) @Column(name = "category")
    private TicketCategory category;

    @Enumerated(EnumType.STRING) @Column(name = "priority")
    private TicketPriority priority;

    @Column(name = "subject", length = 255)
    private String subject;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private TicketStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
