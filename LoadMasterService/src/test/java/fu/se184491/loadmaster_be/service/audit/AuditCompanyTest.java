package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditCompanyTest {

    private static final Company COMPANY = Company.builder().id(9L).build();

    @Test
    void planLeadsToCompanyOfItsTrip() {
        Trip trip = Trip.builder().id(1L).company(COMPANY).build();
        LoadPlan plan = LoadPlan.builder().job(OptimizationJob.builder().trip(trip).build()).build();
        assertThat(AuditCompany.ofPlan(plan)).isEqualTo(9L);
    }

    @Test
    void missingLinksGiveNull() {
        assertThat(AuditCompany.ofPlan(null)).isNull();
        assertThat(AuditCompany.ofPlan(LoadPlan.builder().build())).isNull();
        assertThat(AuditCompany.ofTrip(Trip.builder().build())).isNull();
    }

    @Test
    void accountEventBelongsToCompanyOfTheAccount() {
        User employee = User.builder().id(2L).build();
        employee.setCompany(COMPANY);
        assertThat(AuditCompany.ofAccount(employee)).isEqualTo(9L);
        assertThat(AuditCompany.ofAccount(User.builder().id(3L).build())).isNull();
    }
}
