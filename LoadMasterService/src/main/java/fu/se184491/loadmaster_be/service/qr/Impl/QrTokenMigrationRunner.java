
package fu.se184491.loadmaster_be.service.qr.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.security.qr-migration.enabled",
    havingValue = "true"
)
public class QrTokenMigrationRunner
        implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final AesQrTokenServiceImpl crypto;

    private record TokenRow(
            long id,
            String qrToken
    ) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        log.info("Starting QR token migration to AES-GCM");

        List<TokenRow> rows = jdbcTemplate.query(
            """
            SELECT id, qr_token
            FROM packages
            WHERE qr_token NOT LIKE 'v2:%'
            ORDER BY id
            FOR UPDATE
            """,
            (rs, rowNum) -> new TokenRow(
                rs.getLong("id"),
                rs.getString("qr_token")
            )
        );

        int migrated = 0;

        for (TokenRow row : rows) {
            String plain = crypto.decrypt(row.qrToken());

            // CargoPackage QR token phải là UUID.
            UUID.fromString(plain);

            String encrypted = crypto.encrypt(plain);

            int updated = jdbcTemplate.update(
                """
                UPDATE packages
                SET qr_token = ?
                WHERE id = ?
                  AND qr_token = ?
                """,
                encrypted,
                row.id(),
                row.qrToken()
            );

            if (updated != 1) {
                throw new IllegalStateException(
                    "QR migration update failed for package "
                        + row.id()
                );
            }

            // Kiểm tra mã hóa/giải mã bảo toàn UUID gốc.
            if (!plain.equals(crypto.decrypt(encrypted))) {
                throw new IllegalStateException(
                    "QR migration verification failed for package "
                        + row.id()
                );
            }

            migrated++;
        }

        Integer remaining = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM packages
            WHERE qr_token NOT LIKE 'v2:%'
            """,
            Integer.class
        );

        if (remaining == null || remaining != 0) {
            throw new IllegalStateException(
                "QR migration incomplete"
            );
        }

        // Kiểm tra lại toàn bộ giá trị hiện có.
        List<TokenRow> allRows = jdbcTemplate.query(
            """
            SELECT id, qr_token
            FROM packages
            ORDER BY id
            """,
            (rs, rowNum) -> new TokenRow(
                rs.getLong("id"),
                rs.getString("qr_token")
            )
        );

        for (TokenRow row : allRows) {
            if (!crypto.isCurrentFormat(row.qrToken())) {
                throw new IllegalStateException(
                    "Unexpected QR format for package "
                        + row.id()
                );
            }

            UUID.fromString(
                crypto.decrypt(row.qrToken())
            );
        }

        log.info(
            "QR migration verified: {} migrated, {} checked",
            migrated,
            allRows.size()
        );
    }
}
