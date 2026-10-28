package fu.se184491.loadmaster_be.entity.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketPriority;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

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

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Enumerated(EnumType.STRING) @Column(name = "status")
    private TicketStatus status;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "created_at", length = 19)
    private LocalDateTime createdAt;

    @Convert(converter = LocalDateTimeSecondsConverter.class)
    @Column(name = "resolved_at", length = 19)
    private LocalDateTime resolvedAt;

    @Converter
    public static class LocalDateTimeSecondsConverter implements AttributeConverter<LocalDateTime, String> {
        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String convertToDatabaseColumn(LocalDateTime attribute) {
            if (attribute == null) {
                return null;
            }
            return attribute.truncatedTo(ChronoUnit.SECONDS).format(FORMATTER);
        }

        @Override
        public LocalDateTime convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            String trimmed = dbData.trim();
            if (trimmed.length() > 19) {
                trimmed = trimmed.substring(0, 19);
            }
            return LocalDateTime.parse(trimmed.replace('T', ' '), FORMATTER);
        }
    }
}
