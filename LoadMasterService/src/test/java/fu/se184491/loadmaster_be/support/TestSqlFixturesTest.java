package fu.se184491.loadmaster_be.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class TestSqlFixturesTest {
    @ParameterizedTest
    @CsvSource({
        "proposed-optimization-lifecycle-v2.sql,71ff3d664229572b4efee16f3d1698c656f0d7c7912108382a105c5e563c2d64",
        "proposed-phase2-credit-migration.sql,4cd3fa89f009dccf7733f601406879c39cd691e4a2dd3da4cb08037d776a7af7",
        "proposed-users-timestamp-constraints.sql,2e5c3f893ea734d56208866736954794a6e1e4dfe5eeca9e07701c8c8641f730"
    })
    void classpathSnapshotsMatchReviewedSourceHashes(String filename, String reviewedHash) throws Exception {
        String sql = TestSqlFixtures.read(filename);
        TestSqlFixtures.verify(sql, reviewedHash);
        TestSqlFixtures.verify(sql.replace("\r\n", "\n").replace("\n", "\r\n"), reviewedHash);
        assertFalse(sql.isBlank());
        assertThrows(IOException.class, () -> TestSqlFixtures.verify(sql + "-- changed", reviewedHash));
    }

    @Test void unknownOrTraversalPathCannotFallBackToDocs() {
        for (String filename : new String[]{"missing.sql", "../docs/sql/proposed-optimization-lifecycle-v2.sql"}) {
            assertThrows(IOException.class, () -> TestSqlFixtures.read(filename));
        }
    }
}
