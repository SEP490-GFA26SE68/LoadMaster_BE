package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual JPA binding and transaction rollback, using only the guarded disposable container. */
class LoadPlanOrderedStalePostgresTest {
    @BeforeAll static void start() { DriverDeparturePostgresTest.start(); }
    @AfterAll static void stop() { DriverDeparturePostgresTest.stop(); }
    @BeforeEach void fixtures() { new DriverDeparturePostgresTest().fixtures(); }

    @Test
    void nativeStaleQueryBindsTripAndPreservesOtherTrips() {
        DriverDeparturePostgresTest.insertTrip(11, 1, 9, "PLANNED");
        var sql = DriverDeparturePostgresTest.sql;
        sql.update("INSERT INTO optimization_jobs(id,trip_id,job_uuid,status) VALUES(41,11,'b5-other-job','COMPLETED')");
        sql.update("INSERT INTO load_plans(id,job_id,is_approved,is_stale,version) VALUES(51,41,true,false,1)");
        var repository = DriverDeparturePostgresTest.context.getBean(LoadPlanRepository.class);

        Integer changed = DriverDeparturePostgresTest.tx.execute(s -> repository.markStaleByTripId(10L));

        assertEquals(1, changed);
        assertTrue(sql.queryForObject("SELECT is_stale AND NOT is_approved FROM load_plans WHERE id=50", Boolean.class));
        assertTrue(sql.queryForObject("SELECT NOT is_stale AND is_approved FROM load_plans WHERE id=51", Boolean.class));
    }

    @Test
    void nativeStaleQueryRollsBackWithItsCaller() {
        var repository = DriverDeparturePostgresTest.context.getBean(LoadPlanRepository.class);

        DriverDeparturePostgresTest.tx.execute(s -> {
            assertEquals(1, repository.markStaleByTripId(10L));
            s.setRollbackOnly();
            return null;
        });

        assertTrue(DriverDeparturePostgresTest.sql.queryForObject(
                "SELECT NOT is_stale AND is_approved FROM load_plans WHERE id=50", Boolean.class));
    }
}
