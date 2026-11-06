package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.Trip;

/**
 * Công ty ghi vào {@code audit_logs.company_id}. Mọi hàm trả {@code null} khi không lần ra công ty.
 */
public final class AuditCompany {

    private AuditCompany() {
    }

    public static Long of(Company company) {
        return company == null ? null : company.getId();
    }

    public static Long ofTrip(Trip trip) {
        return trip == null ? null : of(trip.getCompany());
    }

    public static Long ofJob(OptimizationJob job) {
        return job == null ? null : ofTrip(job.getTrip());
    }

    public static Long ofPlan(LoadPlan plan) {
        return plan == null ? null : ofJob(plan.getJob());
    }

    /** Sự kiện về một tài khoản thuộc công ty của tài khoản đó, dù ai làm. */
    public static Long ofAccount(User account) {
        return account == null ? null : of(account.getCompany());
    }
}
