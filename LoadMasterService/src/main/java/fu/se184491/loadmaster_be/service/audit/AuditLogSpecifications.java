package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.entity.company.Company;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Truy vấn nhật ký theo phạm vi và bộ lọc. */
final class AuditLogSpecifications {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private AuditLogSpecifications() {
    }

    static Specification<AuditLog> of(AuditLogScope scope, AuditLogFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            Join<AuditLog, User> user = root.join("user", JoinType.LEFT);
            Join<User, Company> userCompany = user.join("company", JoinType.LEFT);
            // Bản ghi cũ chưa có company_id: lấy công ty của người làm.
            Expression<Long> company = cb.coalesce(root.<Long>get("companyId"), userCompany.<Long>get("id"));

            if (scope.platformOnly()) {
                predicates.add(cb.isNull(company));
            } else if (!scope.everything()) {
                predicates.add(cb.equal(company, scope.companyId()));
            }
            if (filter.from() != null) {
                Instant start = filter.from().atStartOfDay(VIETNAM).toInstant();
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (filter.to() != null) {
                Instant end = filter.to().plusDays(1).atStartOfDay(VIETNAM).toInstant();
                predicates.add(cb.lessThan(root.get("createdAt"), end));
            }
            if (hasText(filter.actionType())) {
                predicates.add(cb.equal(root.get("actionType"), filter.actionType().trim()));
            }
            if (hasText(filter.entityName())) {
                predicates.add(cb.equal(root.get("entityName"), filter.entityName().trim()));
            }
            if (filter.userId() != null) {
                predicates.add(cb.equal(user.get("id"), filter.userId()));
            }
            if (hasText(filter.q())) {
                String pattern = "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("actionType")), pattern),
                        cb.like(cb.lower(root.get("entityName")), pattern),
                        cb.like(cb.lower(root.get("entityId")), pattern),
                        cb.like(cb.lower(user.get("fullName")), pattern),
                        cb.like(cb.lower(user.get("email")), pattern)
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Chuông thông báo: các loại việc cho trước, từ {@code since}; {@code companyId} null là mọi công ty. */
    static Specification<AuditLog> recent(Long companyId, java.util.Collection<String> actions, Instant since) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("actionType").in(actions));
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
            if (companyId != null) {
                Join<AuditLog, User> user = root.join("user", JoinType.LEFT);
                Join<User, Company> userCompany = user.join("company", JoinType.LEFT);
                predicates.add(cb.equal(cb.coalesce(root.<Long>get("companyId"), userCompany.<Long>get("id")), companyId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
