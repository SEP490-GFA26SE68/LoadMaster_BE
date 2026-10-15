package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "contact_of_guests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactOfGuest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interested_plan_id")
    private SubscriptionPlan interestedPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private ContactRequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ContactStatus status;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
