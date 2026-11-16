package fu.se184491.loadmaster_be.service.optimization;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OptimizationV2FeatureFlagAuditTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class))
        .withBean(jakarta.persistence.EntityManagerFactory.class,()->mock(jakarta.persistence.EntityManagerFactory.class))
        .withBean(OptimizationLifecycleReconciler.class,()->mock(OptimizationLifecycleReconciler.class))
        .withUserConfiguration(OptimizationLifecycleScheduler.class);
    @Test void defaultFlagOmitsScheduler() {runner.run(c->assertThat(c).doesNotHaveBean(OptimizationLifecycleScheduler.class));}
    @Test void flagOffAlsoStopsSettlementScheduler() {runner.withPropertyValues("app.optimization.lifecycle-v2.enabled=false").run(c->assertThat(c).doesNotHaveBean(OptimizationLifecycleScheduler.class));}
    @Test void fixtureOnlyEnabledFlagCreatesSchedulerWithoutDatabaseCalls() {
        runner.withPropertyValues("app.optimization.lifecycle-v2.enabled=true").run(c->{assertThat(c).hasSingleBean(OptimizationLifecycleScheduler.class);var jdbc=c.getBean(JdbcTemplate.class);verify(jdbc).afterPropertiesSet();verifyNoMoreInteractions(jdbc);});
    }
}
