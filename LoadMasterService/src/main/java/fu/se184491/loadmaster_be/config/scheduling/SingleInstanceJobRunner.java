package fu.se184491.loadmaster_be.config.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Chạy một job định kỳ trên đúng một máy khi nhiều máy cùng trỏ vào một DB.
 *
 * <p>Giữ advisory lock của PostgreSQL ở mức transaction ({@code pg_try_advisory_xact_lock}) trên một kết nối riêng suốt
 * lúc job chạy; máy khác không lấy được khoá thì bỏ lượt đó. Khoá mức transaction (không phải mức phiên) để vẫn đúng khi
 * kết nối đi qua pooler chế độ transaction. Kết thúc transaction là nhả khoá, kể cả khi máy chết giữa chừng.
 * DB không phải PostgreSQL (H2 khi test) thì chạy thẳng.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SingleInstanceJobRunner {

    /** Không gian khoá riêng của các job LoadMaster ("LM"). */
    static final int LOCK_NAMESPACE = 0x4C4D;

    private final DataSource dataSource;

    /** Khoá cố định của một job: String.hashCode giống nhau trên mọi JVM. */
    static int lockKey(String jobName) {
        return jobName.hashCode();
    }

    /**
     * @return {@code true} nếu máy này đã chạy job; {@code false} nếu máy khác đang giữ khoá hay không mở được kết nối
     */
    public boolean runExclusively(String jobName, Runnable job) {
        try (Connection connection = dataSource.getConnection()) {
            if (!isPostgres(connection)) {
                job.run();
                return true;
            }
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!tryLock(connection, jobName)) {
                    log.debug("Skip job {}: another instance holds its lock", jobName);
                    return false;
                }
                job.run();
                return true;
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            log.warn("Skip job {}: cannot take its lock ({})", jobName, ex.getMessage());
            return false;
        }
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }

    private static boolean tryLock(Connection connection, String jobName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_xact_lock(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, lockKey(jobName));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
