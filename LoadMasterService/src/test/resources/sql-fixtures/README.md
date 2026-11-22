# Frozen PostgreSQL test fixtures

These are reviewed SQL snapshots for disposable Testcontainers only. They are not application migrations and live outside `db/migration`. No production profile loads them.

`TestSqlFixtures` reads from the classpath and verifies the pinned SHA-256 in `provenance.properties`. The regression test also pins independently reviewed hashes. The canonical checksum changes CRLF to LF only; SQL tokens, comments, order, transaction statements, constraints and legacy preservation remain unchanged. `source-sha256` records the original local file bytes for provenance, not a dependency on local docs.

Sources at packaging time:

- `docs/sql/proposed-optimization-lifecycle-v2.sql`: four nullable lifecycle columns; no backfill or ledger writes.
- `docs/sql/proposed-phase2-credit-migration.sql`: historical superseded proposal, retained to characterize ledger/reference/index/CHECK behavior in tests. Do not use for deployment.
- `docs/sql/proposed-users-timestamp-constraints.sql`: existing User constraint audit with deliberate ROLLBACK.

When a proposal intentionally changes, review the SQL diff and preserve its lineage. Compare exact source/fixture bytes, update both provenance checksums and independent test hash intentionally, and rerun PostgreSQL tests. Never silently resync from an untracked docs file in CI, disable checksum validation, or edit production migration history to make fixtures pass.
