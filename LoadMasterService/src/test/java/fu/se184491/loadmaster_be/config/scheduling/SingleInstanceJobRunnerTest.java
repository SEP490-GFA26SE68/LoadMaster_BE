package fu.se184491.loadmaster_be.config.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleInstanceJobRunnerTest {

    private DataSource dataSource;
    private Connection connection;
    private DatabaseMetaData metaData;
    private PreparedStatement statement;
    private ResultSet resultSet;
    private SingleInstanceJobRunner runner;
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        metaData = mock(DatabaseMetaData.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement("select pg_try_advisory_xact_lock(?, ?)")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        runner = new SingleInstanceJobRunner(dataSource);
    }

    @Test
    void lockTaken_runsJob_thenReleasesByEndingTransaction() throws SQLException {
        when(resultSet.getBoolean(1)).thenReturn(true);

        assertTrue(runner.runExclusively("job-a", runs::incrementAndGet));

        assertEquals(1, runs.get());
        verify(statement).setInt(1, SingleInstanceJobRunner.LOCK_NAMESPACE);
        verify(statement).setInt(2, "job-a".hashCode());
        verify(connection).setAutoCommit(false);
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void lockHeldByAnotherInstance_skipsJob() throws SQLException {
        when(resultSet.getBoolean(1)).thenReturn(false);

        assertFalse(runner.runExclusively("job-a", runs::incrementAndGet));

        assertEquals(0, runs.get());
        verify(connection).rollback();
    }

    @Test
    void notPostgres_runsJobWithoutLock() throws SQLException {
        when(metaData.getDatabaseProductName()).thenReturn("H2");

        assertTrue(runner.runExclusively("job-a", runs::incrementAndGet));

        assertEquals(1, runs.get());
        verify(connection, never()).prepareStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void noConnection_skipsJob() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        assertFalse(runner.runExclusively("job-a", runs::incrementAndGet));
        assertEquals(0, runs.get());
    }
}
