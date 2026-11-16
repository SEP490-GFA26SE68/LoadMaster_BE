package fu.se184491.loadmaster_be.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Frozen test-only proposals, never loaded from local docs or discovered by Flyway. */
public final class TestSqlFixtures {
    private static final String ROOT = "/sql-fixtures/";

    private TestSqlFixtures() {}

    public static String read(String filename) throws IOException {
        Properties provenance = new Properties();
        try (var stream = TestSqlFixtures.class.getResourceAsStream(ROOT + "provenance.properties")) {
            if (stream == null) throw new IOException("Missing SQL fixture provenance on classpath");
            provenance.load(stream);
        }
        String expected = provenance.getProperty(filename + ".sha256-lf");
        if (expected == null || filename.contains("/") || filename.contains("\\")) {
            throw new IOException("Unknown SQL test fixture: " + filename);
        }
        try (var stream = TestSqlFixtures.class.getResourceAsStream(ROOT + filename)) {
            if (stream == null) throw new IOException("Missing SQL fixture on classpath: " + filename);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            verify(sql, expected);
            return sql;
        }
    }

    static void verify(String sql, String expected) throws IOException {
        try {
            // Git checkout may change line endings; do not normalize any SQL tokens or whitespace otherwise.
            byte[] canonical = sql.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            if (!actual.equals(expected)) throw new IOException("SQL test fixture checksum mismatch");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
