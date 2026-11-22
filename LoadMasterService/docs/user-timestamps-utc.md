# User system timestamps: UTC / Instant

Scope: only `User.createdAt`, `User.updatedAt` and their direct consumers. Other entities, local dates/times,
subscription expiry, OTP, authentication and Keycloak flows are outside this change.

## Mapping and API

Before: `LocalDateTime` with a String attribute converter, `varchar(19)` column definition and regex checks.
The explicit string mapping made Hibernate propose a timestamp-to-varchar conversion against existing TIMESTAMPTZ columns.
Removing only `@Convert` does not remove the explicit `varchar(19)` mapping.

After: `Instant` with `@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)`. The PostgreSQL dialect resolves this to
native `timestamp with time zone`. The existing NOT NULL, immutable creation time and updated-after-created check remain.
Callbacks retain whole-second precision for new values, preserve creation time on update and clamp updates to creation time
if the clock is behind it. Existing database fractional seconds are preserved on reads; no historical data is rewritten.

`UserListItemResponse.createdAt` is now an ISO 8601 UTC string, e.g. `2026-11-04T15:00:00Z`, instead of
`2026-11-04 15:00:00`. This is an API breaking change for clients that parse the old format. The same DTO is nested in
create-user and reset-password responses and returned by list/update/status operations. `subscriptionExpiresAt` retains
its existing type and format. No new field is added to the current-user profile or any other DTO.

The inactive-user query now accepts an Instant. Its cutoff keeps three calendar months, calculated in UTC rather than
the JVM's default zone; it is not approximated as 90 days. Existing deletion/Keycloak behavior is unchanged and is tested
only with mocked repositories/clients.

## Shared database: inspect before deploying

The configured database was inspected using SELECTs in a read-only transaction, verified with
`current_setting('transaction_read_only') = 'on'`, and rolled back. No schema or data was changed. Run
`docs/sql/inspect-users-timestamps.sql` through an
approved read-only connection to inspect actual column types, precision, defaults, checks, triggers and invalid/null values.
The script ends with ROLLBACK. The source annotations alone cannot establish which constraints exist in the live database.

Read-only snapshot on 2026-11-04: `public.users.created_at` and `updated_at` are TIMESTAMPTZ with precision 6,
both have no default, creation time is NOT NULL, and update time is nullable. Only status/role checks exist; no
timestamp regex check, updated-after-created check or user-defined trigger was found. All 9 rows have both timestamps
and satisfy updated-at >= created-at. Recheck before any deployment because the database is shared.

No type conversion is needed for this database. `docs/sql/proposed-users-timestamp-constraints.sql` is a separate,
unexecuted proposal to align updated-at NOT NULL and the ordering check with the entity contract. It takes a table lock
with a short timeout, verifies types and data again, aborts on invalid rows, preserves existing data, and defaults to
ROLLBACK. Test and review it separately before replacing ROLLBACK with COMMIT. It is intentionally outside Flyway's path.

- If both columns are already TIMESTAMPTZ and there are no obsolete string-format constraints/defaults/triggers, no type
  migration or data conversion is required. Keep `chk_users_updated_after_created` (or the equivalent existing constraint).
- Removing a Hibernate `@Check` annotation does NOT remove an existing PostgreSQL constraint. If an obsolete regex check
  remains (possibly casting timestamps to text), inspect its actual definition first. A separate, reviewed transaction may
  remove only that obsolete check. Do not remove updated-after-created or other business checks. No such DDL is auto-run here.
- If another environment has VARCHAR timestamps from V13, stop and plan a separate migration. Validate every legacy value,
  confirm the timezone in which it was originally written (V13 used Asia/Ho_Chi_Minh for some generated values but stripped
  offsets from other values), inventory defaults/triggers/checks, and review conversion on a snapshot. Never infer timezone
  or silently truncate, replace or repair historical values.
- Historical `src/main/resources/db/migration/V13__format_users_created_updated_at.sql` is retained unchanged to avoid
  invalidating applied Flyway checksums. It converts timestamps to VARCHAR and must not be replayed on the shared database.
  Audit migration history before enabling Flyway; review a corrective migration separately if needed. The directory also
  contains pre-existing duplicate migration version numbers, which need separate review before enabling Flyway.
- The application default is now `ddl-auto=validate` (previously `update`). `application-dev.yml` inherits this value;
  no personal/local profile or repository deployment override of it was found. Flyway stays disabled. No dependencies or
  automatic migration execution were added. Audit externally managed environment variables, JVM flags and command-line
  settings at deployment because Spring can override repository configuration. A whole-schema validate may expose
  unrelated existing mismatches; this change does not repair them or silently switch back to update.
- Instant mapping carries an absolute UTC instant without changing global JVM/JDBC settings. No global timezone override
  was added, since it could alter unrelated legacy LocalDateTime mappings. Frontends should convert instants for display.

## Verification

Use Java 21. Build without starting the backend:

```powershell
mvn -B -DskipTests package
mvn -B '-Dtest=UserTimestampTest,UserTimestampPostgresTest,UserServiceImplTest,UserControllerTest,CompanyAgreementServiceTest' test
```

The selected service tests use Mockito; MVC tests do not load JPA. The new PostgreSQL test creates a new Testcontainers
database, creates only its own fixture tables, and boots Hibernate directly with `hbm2ddl.auto=validate`. It never loads
application profiles, schedulers, Flyway, Keycloak or shared datasource settings. Schema-update SQL is generated to a Writer
only, never executed, and is checked for unwanted column ALTERs. It verifies offset/microsecond reads, timestamp types,
lifecycle writes and the Spring Data Instant cutoff query. It skips if Docker is unavailable: a skip is not proof of a
PostgreSQL round trip. No `create`, `create-drop`, cleanup DML, volume deletion or schema reset is used.

Verified on 2026-11-04 with Spring Boot 4.1.1, Hibernate 7.4.5.Final and Java 21: package build successful; all
42 selected tests passed, zero failures/errors/skips. The PostgreSQL container ran PostgreSQL 17.11. The tests verified
both offset input and a non-UTC database session. No main application or scheduled job was started.

Reference: [Hibernate 7.4 temporal mappings](https://docs.hibernate.org/orm/7.4/userguide/html_single/#basic-instant),
[PostgreSQL date/time types](https://www.postgresql.org/docs/current/datatype-datetime.html).

## Follow-up audit: migration history, DDL and API

This follow-up changes only `src/main/resources/application.yml`, the MVC serialization tests in
`UserControllerTest.java`, the new `UserTimestampMigrationAuditTest.java`, and this report. Historical migration files,
the proposed constraint script and all other production source remain unchanged from the initial refactor.
No shared database was accessed during this follow-up. The read-only snapshot above belongs to the initial refactor.

### V13 behavior and compatibility

There are TWO files named version 13:

* `V13__add_route_leg_guidance_steps.sql`: adds `route_legs.steps JSON`; changes no timestamps.
* `V13__format_users_created_updated_at.sql`: touches only `public.users.created_at` and `public.users.updated_at`.
  It removes their defaults, converts both to VARCHAR(19), fills NULL/regex-invalid creation values with current local
  time, overwrites missing/regex-invalid/out-of-order updates with creation time, sets both NOT NULL, adds string defaults,
  two regex checks and `chk_users_updated_after_created`. Those data repair operations are not acceptable as an implicit
  rollout of this refactor. They were exercised only against controlled test fixtures, not shared data.

For native timestamp input, V13 casts to TIMESTAMPTZ and formats in `Asia/Ho_Chi_Minh`. The cast of a timestamp WITHOUT
timezone first depends on the database session timezone. For text input it replaces T and takes the first 19 characters,
discarding offsets/fractions without establishing their original zone. Thus not all historical strings can be presumed
UTC, or even presumed Vietnamese local time. Its regex also does not establish calendar-valid dates (e.g. February 31).

A PostgreSQL test demonstrates that `2026-11-04T15:00:00.123456Z` becomes `2026-11-04 22:00:00` after unmodified V13.
Knowing the source zone for this controlled fixture recovers the whole-second instant, but not the fractional seconds.
V13 output is incompatible with User's Instant/TIMESTAMP_WITH_TIMEZONE mapping. Current TIMESTAMPTZ values must not be
converted back to text or have another timezone offset applied.

### Complete SQL directory review (22 files)

| Version / files | Scope and timestamp findings | Bootstrap / compatibility finding |
|---|---|---|
| V1 | Trip status, stop coordinates, planned/actual arrival TIMESTAMP | Conditional ALTER only; does not create base tables |
| V2 | Packages; delivery requirements and package links; deadline/created_at TIMESTAMP | Requires packages, companies and users; V1+V2 on empty PostgreSQL fails on missing packages |
| V3 | Vehicle hazardous capability | Conditional ALTER; requires prior vehicle table for useful effect |
| V4 | Subscription plan columns, subscriptions, credits, payment tables; initial dates TIMESTAMP | Requires subscription_plans/companies; V14/V15 format migrations later change selected dates to VARCHAR |
| V5 | Cargo package packing profiles | Requires packages |
| V6 | Route plans, legs, suggestions; created_at TIMESTAMP | Requires trips/delivery_stops; current route entity timestamps are LocalDateTime |
| V7 | GPS, exceptions, ETA alerts, reroutes; times TIMESTAMP | Requires trips/users; current corresponding entity timestamps remain LocalDateTime |
| V8 | Package name/note | Requires packages |
| V9 | Payment order_type/target_id | Conditional ALTER; no User timestamp changes |
| V10_next__add_trip_origin_fields.sql | Trip origin columns | Invalid version naming; ignored by normal scanning / rejected with strict naming |
| V11_add_trip_delivery_stops.sql | Delivery stop requirement FK | Missing double underscore separator; ignored / rejected with strict naming |
| V12_add_columns_delivery_requirement.sql | Customer FK, package count/note | Missing double underscore separator; ignored / rejected with strict naming; requires customers |
| V13 x2 | User timestamp formatter; route leg steps | Duplicate version; User formatter conflicts with Instant |
| V14 x2 | Warehouses with TIMESTAMPTZ; subscription dates and credit created_at to VARCHAR | Duplicate version; billing formatters match their current unrelated converter mappings and are not changed here |
| V15 x2 | Trip origin warehouse; payment timestamps to VARCHAR plus immutability trigger | Duplicate version; payment mapping remains unrelated LocalDateTime + converter |
| V16 | Vehicle door dimensions | Requires vehicle_types |
| V17 | Company agreements; deadline/signed/created/updated TIMESTAMP | Requires companies/users; created_at/updated_at entity fields are Instant, a separate existing schema mismatch to review |
| V18 | Vehicle axle configuration/checks | Requires vehicle_types |
| V19 | Unplaced-package rejection check | Requires unplaced_packages and existing named check; not a base-table creation |

No file creates base `users`, `companies`, `packages`, `trips`, `delivery_stops`, `vehicle_types`, `customers`,
`subscription_plans` or `unplaced_packages`. Therefore this is an incremental history for a pre-existing schema, not a
reproducible bootstrap. Correcting duplicate/naming issues alone cannot make fresh-database Hibernate validation pass.
Adding one timestamp migration at the end cannot fix these earlier blockers. The isolated Flyway tests prove duplicate
resolution failure, strict filename failure, and V2's missing-baseline failure. A passing audit test means the blocker
was reproduced as expected; it does NOT mean the full migration chain produced a valid schema.

### Safe history decision required before enabling Flyway

No new versioned migration was added, renamed or edited. A type migration is unnecessary on the already-TIMESTAMPTZ
database, and the source zone of other environments' legacy strings has not been established. A new executable migration
with a guessed zone would risk silently shifting instants.

1. Inventory `flyway_schema_history` per environment through approved read-only access: version, script, checksum,
   success and actual schema. Establish which conflicting files, if any, were ever applied. Do not run `repair`, change
   checksums, rename applied files or use baseline-on-migrate to conceal missing tables without a reviewed plan.
2. Team/DBA must choose a canonical baseline for brand-new databases and a separate upgrade path for existing ones.
   A reviewed full-schema baseline should be built from the approved schema, include native User TIMESTAMPTZ, resolve
   missing base tables and unrelated mismatches, and pass full Hibernate validate in a disposable PostgreSQL database.
   Keep existing applied histories immutable. Only renumber never-applied files after confirming this across environments.
3. If any existing environment has VARCHAR timestamps, prepare a uniquely versioned append-only corrective migration only
   AFTER lineage and source zone are confirmed. Check every value for real date validity, NULLs, ordering, defaults,
   regex checks and triggers; preserve the original strings/audit evidence. Interpret a confirmed local timestamp with
   `value::timestamp AT TIME ZONE '<confirmed-zone>'`, never an assumed UTC cast or another shift of a TIMESTAMPTZ value.
   Mixed-zone provenance needs explicit per-record rules or manual reconciliation. Lost V13 fractions cannot be recreated.
4. Rehearse baseline/upgrade on snapshots and compare row counts and instants before/after; approve separately from this
   source refactor. No migration or Flyway activation against shared databases is authorized by these test commands.

### DDL configuration and deployment

`application.yml`: update -> validate. `application-dev.yml`: no JPA override, so inherits validate. No personal local
profile exists and none was added. Docker Compose defines infrastructure only; no backend JPA/Flyway override was found.
No matching override was found in the process environment, local .env, repository .mvn or CI configuration inspected.
External deployment configuration is not observable here and must be checked by the owner. Existing unrelated H2 tests
have their own create-drop settings; they were neither changed nor run by this follow-up.

Flyway is already integrated through `spring-boot-starter-flyway` and `flyway-database-postgresql`, but application.yml
explicitly sets `spring.flyway.enabled=false`. It remains false; no dependency or migration activation change was made.
Current baseline-on-migrate=true / baseline-version=0 settings remain inert while disabled and require separate review.
Do not start the whole backend against the shared database to test this change: initializers/jobs may write even when DDL
is validate. Validate checks mapping; it does not install missing CHECK/NOT NULL constraints or certify every data row.

### Frontend breaking-change inventory

Only the User creation instant's format changed; all other timestamps and envelope fields retain their contracts.

| Endpoint | JSON field |
|---|---|
| POST /api/users | $.data.user.createdAt |
| GET /api/users | $.data[*].createdAt |
| PUT /api/users/{id} | $.data.createdAt |
| PATCH /api/users/{id}/status | $.data.createdAt |
| POST /api/users/{id}/reset-password | $.data.user.createdAt |

Old format: yyyy-MM-dd HH:mm:ss without an offset. New format: ISO 8601 UTC, e.g. 2026-11-04T15:00:00Z, with fractional
seconds possible on existing rows. Frontends must parse it as an instant and format once in the chosen display zone;
do not append a second Z, apply a fixed +07 adjustment, or parse it as a naive local date. No frontend code was edited.
No timestamp field was added to GET /api/users/me or /stats. DELETE /api/users/{id} has no timestamp response.
The reset-password service/logic was not changed; its nested DTO was tested only with a mocked service. MVC tests cover
all five envelope paths and assert the exact UTC string; the controlled +07 input becomes 15:00Z once. The subscription
expiry formatter stays on its LocalDateTime field only, with its previous output asserted separately.

### Constraint proposal review and invalid-data procedure

The proposed constraint script is unchanged and remains outside automatic Flyway locations. It checks both native column
types and all NULL/out-of-order values under a short exclusive table lock, aborts if any invalid row is found or the
named ordering check already exists, and never updates/backfills data. The updated-at >= created-at rule preserves the
existing callback business invariant, including equality at second precision and clock-behind-creation clamping.
Because SQL CHECK permits NULL, NOT NULL is a separate proposal requiring approval; neither is applied by Hibernate validate.

If preflight fails: leave ROLLBACK in place, save a snapshot/audit evidence, identify affected rows through approved
access, and obtain a documented per-record correction rule from the business owner. Do not infer missing update times
from creation time or overwrite values automatically. Recheck counts and order after a separately authorized remediation.
If the named check already exists, inspect it before deciding whether any DDL is needed; the proposal intentionally fails
instead of replacing an unknown constraint. Plan lock timing and validate current data again immediately before application.

The new PostgreSQL test rehearses the proposal body inside an explicit test transaction: verifies NOT NULL/CHECK reject
new violations, confirms the original microsecond instant is preserved, rolls back and verifies schema restoration.
It then supplies NULL/out-of-order fixtures and confirms the script aborts with those rows/counts unchanged. It never
commits proposed DDL or runs on shared data. A test explicitly normalizes JDBC timestamps to Instant so assertions are
independent of PostgreSQL session display timezone.

Follow-up validation: `mvn -B
'-Dtest=UserTimestampTest,UserTimestampPostgresTest,UserTimestampMigrationAuditTest,UserServiceImplTest,UserControllerTest,CompanyAgreementServiceTest'
package` completed successfully on Java 21 / Boot 4.1.1 / Hibernate 7.4.5.Final / PostgreSQL 17.11. All 49 selected tests
passed with zero errors, failures or skips (16 MVC, 3 migration/constraint audit, 1 PostgreSQL mapping/round-trip, 4 lifecycle/
metadata, 18 account service and 7 company-agreement service). All main and test source compiled. The expected Flyway
errors in the audit log are asserted historical blockers; this is not a successful full-schema bootstrap. No unrelated
test suites were run or repaired. Existing warnings about deprecated Hibernate APIs/Mockito instrumentation are outside scope.
