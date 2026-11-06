> Kết quả tiếp nối: full-clone rehearsal và Hibernate validate Bước2D.1 xem [bàn giao2D.1](../phase2d1/README.md). Báo cáo2C bên dưới giữ nguyên bối cảnh lịch sử.

# LoadMaster — Bước 2C: đề xuất migration, chưa áp dụng

Snapshot READ ONLY: **2026-11-05 07:15:37.840592+00:00**, PostgreSQL **17.11 (Debian 17.11-1.pgdg13+2)**. Spring/Python `.env` trỏ cùng target. Connection ép `default_transaction_read_only=on`; transaction REPEATABLE READ READ ONLY; kiểm tra `transaction_read_only=on` đầu/cuối. Chỉ đọc metadata, catalog và số liệu tổng hợp; không lấy UUID/company/user/ledger reference hay balance của người dùng vào báo cáo.

**Không có migration/catalog update nào được chạy trên database chung.** Không bật Flyway, không thay history/checksum/baseline, không sửa runtime, FE, GA/AI, subscription, billing hoặc refund. Không commit/push.

## Kết luận PASS / BLOCKER / WARNING

| Mục | Đánh giá | Thực tế và hành động đề xuất |
| --- | --- | --- |
| CHECK thuật toán | **BLOCKER** | `optimization_jobs_algorithm_name_check` chỉ nhận EP_DBLF/GENETIC_ALGORITHM và cho qua NULL theo ngữ nghĩa CHECK. Chặn tên của PRO/ULTIMATE. Đề xuất 03, chưa áp dụng |
| job_uuid | **PASS** | varchar(64), nullable; có UNIQUE hợp lệ `uk2t1ly949hnjlhly02pe9cku30`. 0 NULL/blank/duplicate. Giữ index này, không tạo bản thứ hai |
| Cột Phase 2 | **PASS** | company_id varchar(64), subscription_tier/algorithm_tier varchar(50), parent_plan_id bigint, seed integer đều có và nullable. Không cần ADD/ALTER cột |
| Credit idempotency | **BLOCKER** | Có idx_credit_reference nhưng không unique; thiếu partial unique USAGE(reference). Đề xuất 02 |
| Catalog | **BLOCKER** về đồng bộ chính sách | BASIC 3000, PRO **10000**, ULTIMATE 30000; initializer đã PRO15000. Đề xuất 04 riêng, cần chốt ngày/chu kỳ hiệu lực |
| Job active | **BLOCKER vận hành trước mở lại admission** | PENDING **0**, RUNNING **6**; cả 6 không có USAGE và thiếu ít nhất một snapshot. Không đủ bằng chứng nói worker đang sống hay đã chết. Phân loại/đối soát thủ công; tuyệt đối không backfill billing/reset/refund tự động |
| Flyway | **WARNING** | Starter/dependency đã có nhưng application.yml enabled=false, ddl-auto=validate. `.env` không override hai cờ. Bảng history tồn tại: **true**. Không đọc/sửa nội dung history. Source có trùng V13/V14/V15; không bật Flyway để chạy bộ đề xuất |
| Kiểm thử đề xuất | **PASS có giới hạn** | 25 kịch bản trên PostgreSQL17 Docker dùng riêng; metadata tái dựng + dữ liệu tổng hợp, không phải clone đầy đủ hay E2E/Hibernate validate |

## 1. Schema hiện tại → mục tiêu

- `optimization_jobs.algorithm_name`: varchar(255) → giữ nguyên kiểu. CHECK mở rộng thành `EP_DBLF`, `EP_DBLF_GA`, `EP_DBLF_GA_AI`, **giữ GENETIC_ALGORITHM và NULL**. Không UPDATE algorithm_name của job nào. Thực tế có **24 job, tất cả EP_DBLF**.
- `optimization_jobs.job_uuid`: giữ varchar(64), nullability và unique index hiện tại. Không ép NOT NULL, không chuẩn hóa UUID hay backfill job cũ. Idempotency-Key không có cột/table riêng: reserve() derive UUID từ company/key, UNIQUE(job_uuid) bảo vệ reservation và partial unique USAGE bảo vệ ledger. Java cũng chưa khai báo nullable=false; Python nullable=false không tự thay schema này.
- Các snapshot company/tier/parent/seed giữ nguyên cột/kiểu/nullability. Script 02 chỉ assert kiểu đã review; nếu môi trường khác thiếu cột hoặc sai kiểu thì **dừng**, không đoán ALTER TYPE/ADD COLUMN. Cần proposal riêng cho môi trường đó.
- `credit_transactions.reference`: varchar(100), nullable; giữ idx_credit_reference phục vụ lookup các loại giao dịch. Thêm đúng một unique partial index trên reference **WHERE type='USAGE'**; không unique toàn cục reference vì REFUND cần cùng job reference. Script tái sử dụng index tương đương tên khác, kiểm tra valid/ready/immediate/key/predicate; tên mục tiêu bị chiếm bởi index sai thì dừng.
- Không thêm global FK reference → job_uuid: reference dùng nhiều loại giao dịch. Link job/company và charge/refund được runtime hiện có kiểm tra. Partial unique không tự bảo đảm UUID không rỗng/không mồ côi; preflight dừng/đánh dấu để đối soát, runtime mới vẫn phải gửi UUID hợp lệ.
- `credit_accounts`: balance CHECK >=0, UNIQUE(company_id), FK company; ledger có FK credit_account_id và CHECK type MONTHLY_GRANT/PURCHASE/USAGE/REFUND. Giữ nguyên tất cả. Không thấy user trigger/rule trên bốn bảng được kiểm tra hoặc event trigger toàn DB. Script04 vẫn từ chối custom trigger/rule nếu xuất hiện trước lúc apply, để không gây grant/balance side effect. Snapshot hiện tại **0 credit_transactions**, do đó 0 invalid/duplicate USAGE reference không phải bằng chứng đã thử billing thật.
- `optimization_jobs.created_at` vẫn timestamp without time zone; CreditTransaction.created_at vẫn varchar(19). Không đổi thời gian hay constraint ngày giờ trong 2C. CHECK status/objective và các FK/unique khác giữ nguyên.

## 2. Đối chiếu Java / Python / database

| Ý nghĩa | Java | Python | DB mục tiêu |
| --- | --- | --- | --- |
| BASIC | EP_DBLF | AlgorithmName.EP_DBLF | EP_DBLF |
| PRO | EP_DBLF_GA | AlgorithmName.EP_DBLF_GA | EP_DBLF_GA |
| ULTIMATE | EP_DBLF_GA_AI | AlgorithmName.EP_DBLF_GA_AI | EP_DBLF_GA_AI |
| Legacy | OptimizationAlgorithm.GENETIC_ALGORITHM | Model nhận string, không có member canonical tương ứng trong AlgorithmName | Giữ GENETIC_ALGORITHM để bảo toàn lịch sử |

Java OptimizationAlgorithm có 4 tên; billing AlgorithmTier có 3 canonical tên. Python AlgorithmTierService còn DEFAULT_GREEDY và alias BASIC/PRO/ULTIMATE/GA/AI: **không thêm chúng vào DB CHECK**, không đổi dispatch hay hợp thức hóa alias thành stored algorithm_name. `optimization_jobs.algorithm_tier` hiện là snapshot subscription tier BASIC/PRO/ULTIMATE, khác trường algorithm_name và khác catalog algorithm_tier EP_DBLF/EP_DBLF_GA/EP_DBLF_GA_AI.

Source: Java `constant/optimization/OptimizationAlgorithm.java`, `constant/billing/AlgorithmTier.java`, `service/optimization/OptimizationAdmissionService.reserve()` và `OptimizationEntitlementService.requireActive()`; Python `app/service/optimize/algorithm_tier_service.py`, `app/entity/optimization/optimization_job.py`, `authorized_job_dispatch.require_authorized_job()`. Mở CHECK không chứng minh GA/AI đã được triển khai hay được dispatch đúng; 2C không thay module đó.

## 3. Review proposal cũ

`../proposed-phase2-credit-migration.sql` **không dùng để triển khai mới**; phần executable được giữ để truy vết, chỉ thêm header superseded.

1. Đúng: không DELETE ledger/job, không balance DML, giữ legacy GENETIC_ALGORITHM/NULL, không backfill job cũ, transaction + lock/statement timeout.
2. Sai với schema thực tế: `CREATE UNIQUE INDEX IF NOT EXISTS uk_phase2_job_uuid` chỉ xét tên, không thấy UNIQUE tên khác đã có nên tạo index trùng. Tương tự với partial index đã tồn tại tên khác; index trùng tên nhưng sai định nghĩa có thể bị bỏ qua.
3. Không chạy lại được: sau lần đầu CHECK đã mở rộng, guard chỉ chấp nhận chuỗi CHECK cũ nên lần hai abort.
4. So sánh đúng nguyên chuỗi pg_get_constraintdef phụ thuộc biểu diễn cast ARRAY. Restore/reparse trên PostgreSQL test cho định nghĩa tương đương nhưng chuỗi khác và old proposal abort. Proposal mới chỉ bỏ formatting và cast text/varchar đã review, vẫn đối chiếu đúng whitelist/logical tokens; CHECK tùy chỉnh khác phải dừng.
5. `ADD COLUMN IF NOT EXISTS` không xác minh kiểu cột đã có. Thực tế không thiếu cột nên bỏ ADD khỏi proposal mới; assert kiểu trước index.
6. Không có khóa tường minh bao trùm preflight trong proposal cũ; concurrent writer có thể chen vào giữa bước kiểm tra và DDL rồi khiến index build thất bại. Không phải âm thầm mất dữ liệu, nhưng cần freeze/drain + khóa + rollback được review như proposal mới.
7. Tách CHECK thuật toán khỏi idempotency Credit và catalog. Nhờ vậy có thể review/rollback/áp dụng độc lập; tránh một cập nhật quota bị lẫn với DDL idempotency.

## 4. Bộ file đề xuất

- `01-preflight.sql`: READ ONLY, schema/constraint/index/catalog và job/billing aggregate. Nếu không có row PENDING trong GROUP BY nghĩa là 0. Thiếu cột khiến truy vấn fail cũng là tín hiệu dừng, không tự tạo cột.
- `02-credit-idempotency.sql`: assert kiểu/data; reuse UUID unique; chỉ tạo unique index thật sự thiếu. Không cột/DML/backfill.
- `03-algorithm-check.sql`: đổi đúng CHECK đã review; giữ legacy/NULL; re-run nhận diện target và validate, không drop/recreate khi đã đúng.
- `04-pro-catalog.sql`: riêng business approval; chỉ đúng một PRO_MONTHLY, tier PRO, cycle MONTHLY, algorithm EP_DBLF_GA, active=true, monthly_credits10000 →15000. Đã15000 thì no-op; giá trị/identity khác hoặc thiếu row thì abort. Không tự insert/reactivate.
- `05-postflight.sql`: READ ONLY assert UUID/USAGE unique index, CHECK đích validated, reference hợp lệ; report catalog match và active-job counts. Catalog mismatch được báo false thay vì chặn phần schema vì 04 cần duyệt riêng. Phải kiểm tra đủ ba row, đúng tier/cycle/active trong 01 trước khi đóng ticket.
- `schema-fixture.json`: chỉ metadata và snapshot text proposal cũ, không customer data. Để test tái lập độc lập với DB chung.
- `test_phase2c_migration.py`: private Docker rehearsal; không nhận external DB URL. Chỉ các fixture tổng hợp trong container này được DROP/UPDATE/DELETE.

Tất cả nằm **ngoài src/main/resources/db/migration**. Không đặt tên Vxx hay tự thêm vào Flyway.

## 5. Catalog PRO và hiệu lực

`SubscriptionPlanInitializer.run()` đã `.monthlyCredits(15000)` nhưng chỉ insert khi plan_code chưa có; restart không sửa row PRO_MONTHLY cũ. DB hiện là10000, BASIC/ULTIMATE và các algorithm_tier đều đúng.

04 chỉ sửa `subscription_plans.monthly_credits`; không sửa CreditAccount.balance, CreditTransaction, CompanySubscription, expiry, auto_renew, active, price, features hay tier. Không gọi grantMonthlyCredits/refund/deduct. Team phải xác nhận từ lần mua/gia hạn nào quota mới có hiệu lực; runtime activation hiện đọc plan.getMonthlyCredits rồi grant, nên các lần activation/gia hạn sau cập nhật có thể sử dụng15k theo logic sẵn có.

**WARNING về response hiện có:** `ContractServiceImpl.mapToResponse()` lấy `creditsGranted = entity.getPlan().getMonthlyCredits()`, không lấy lịch sử grant. Vì cùng row catalog, một GET hợp đồng hiện tại có thể hiển thị15k sau 04 dù số đã cấp trước đó là10k. Không cấp thêm Credit trong SQL, nhưng không thể hứa response hợp đồng hoàn toàn không đổi. Team cần review ý nghĩa field/hiệu lực và nếu cần xử lý lịch sử bằng một nhiệm vụ runtime riêng; 2C không sửa nó.

## 6. Kết quả rehearsal

**25/25 pass**, PostgreSQL17 container mới, exit0. Không dùng URL DB chung hay import cấu hình app. Schema4 bảng được tái dựng từ metadata; companies/trips và company_subscriptions là fixture tối thiểu, dữ liệu job/catalog/account/ledger đều tổng hợp. Không phải pg_dump clone đầy đủ, không test toàn bộ Flyway, Hibernate validate, sequence/identity/default hoặc deployment production.

Bao phủ: giữ nguyên toàn bộ row/constraint ngoài mục tiêu; chạy lại cả02/03/04 không đổi thêm; GENETIC_ALGORITHM/NULL tồn tại trước migration vẫn còn; chấp nhận đúng4 tên, từ chối UNKNOWN; reuse index tên khác; index cùng tên sai thì abort; UUID unique thực sự thiếu thì tạo; duplicate/NULL/blank UUID hoặc USAGE thì abort không sửa data; partial unique chặn USAGE trùng nhưng không chặn REFUND cùng reference; thiếu/sai kiểu cột và custom CHECK thì abort; custom catalog trigger có ý định cộng balance bị chặn trước UPDATE, balance fixture không đổi; catalog chỉ đổi monthlyCredits/no-op và từ chối nhiều giá trị sai; read-only pre/post không thay row/schema; lock timeout rollback không có DDL dang dở.

Old-proposal evidence: raw proposal từ chối CHECK tương đương sau reparse (transaction rollback). Trong test riêng, chỉ hiệu chỉnh chuỗi expected của guard theo clone để đi tới phần DDL: nó tạo UUID index thứ hai và abort khi chạy lại. Không sửa guard ở proposal thật hoặc database chung. Đây là phép tái hiện có hiệu chỉnh biểu diễn, không tuyên bố đã áp dụng old proposal nguyên văn thành công lên clone DB chung.

Log: `D:/VNR_game/phase2c-rehearsal.log`, exit `phase2c-rehearsal-exit.txt`; metadata/aggregate `phase2c-readonly-results.json`. Snapshot READ ONLY sau rehearsal vẫn cho CHECK cũ, PRO10000 và chưa có partial USAGE unique — DB chung chưa được áp dụng.

## 7. Runbook thủ công — chỉ sau khi team phê duyệt

1. Review diff và hash từng script. Xác nhận đúng dev target bằng kênh vận hành, role/schema/permissions. Không lấy `.env` hoặc password dán vào terminal/log. Chốt owner thao tác, maintenance window, catalog effective cycle và tiêu chí rollback. Không chạy cả thư mục bằng wildcard.
2. Đối soát6 RUNNING thiếu billing/snapshot qua log/quy trình vận hành riêng: xác định có worker thật hay là legacy. Không kết luận “treo” chỉ theo status; không tự reset PENDING, tái dispatch, tạo USAGE, refund hoặc thay UUID. Ghi nhận xử lý được team duyệt trước khi mở admission.
3. Freeze tạo optimization mới/rerun và các writer liên quan; drain worker đang chạy thật. Với catalog, dừng/phối hợp activation, renewal và payment callbacks để không đổi quota giữa một nghiệp vụ. Không bypass security. Đây là kế hoạch, chưa stop service nào trong 2C.
4. DBA tạo backup full custom-format và schema-only với PG service/credential store đã quản lý; lưu restricted/encrypted, timestamp và hash. Ví dụ PowerShell (chỉ minh họa, chưa chạy):

```powershell
pg_dump --dbname="service=loadmaster_dev" --format=custom --file="<backup-dir>/loadmaster-before-2c.dump"
pg_dump --dbname="service=loadmaster_dev" --schema-only --file="<backup-dir>/loadmaster-before-2c-schema.sql"
pg_restore --list "<backup-dir>/loadmaster-before-2c.dump"
```

5. Restore thử vào **database clone riêng được phép xóa**, xác nhận full restore, schema/constraints/indexes và restore procedure. Harness hiện tại không thay thế bước này. Không pg_restore đè/clean database chung. Lưu bản before catalog và schema DDL thực tế; baseline row/hash riêng được kiểm soát để so sau maintenance.
6. Chạy01 bằng psql với `ON_ERROR_STOP=1`, lưu output riêng; đối chiếu metadata với snapshot. Dừng nếu duplicate/invalid reference, kiểu khác, custom constraint/index/trigger/rule/event trigger, catalog khác hoặc có writer/job mới chưa phân loại. Không xóa ledger hay sửa dữ liệu để “vượt preflight”.
7. Trên clone đầy đủ, chạy lần lượt02 rồi03; kiểm tra05 và01; chạy lại02/03 để kiểm tra no-op. Chạy04 riêng chỉ khi business đã duyệt, rồi05/01. Đối chiếu toàn bộ row/balance/ledger/subscription và unrelated DDL với backup. Sau khi clone được nghiệm thu, DBA mới áp dụng đúng sequence này trên dev đã freeze:

```powershell
psql --dbname="service=loadmaster_dev" --set=ON_ERROR_STOP=1 --file="docs/sql/phase2c/01-preflight.sql"
psql --dbname="service=loadmaster_dev" --set=ON_ERROR_STOP=1 --file="docs/sql/phase2c/02-credit-idempotency.sql"
psql --dbname="service=loadmaster_dev" --set=ON_ERROR_STOP=1 --file="docs/sql/phase2c/03-algorithm-check.sql"
# 04 is a separate release/business decision; omit it until explicitly approved.
psql --dbname="service=loadmaster_dev" --set=ON_ERROR_STOP=1 --file="docs/sql/phase2c/04-pro-catalog.sql"
psql --dbname="service=loadmaster_dev" --set=ON_ERROR_STOP=1 --file="docs/sql/phase2c/05-postflight.sql"
```

Mỗi file có transaction riêng; bất kỳ exit khác0 thì **dừng sequence**, không tiếp tục bước sau. Lock_timeout5s, statement_timeout60s (catalog30s); không auto-retry. Có cửa sổ chỉ02 đã commit nhưng03 fail: giữ admission đóng, đối chiếu rồi forward-fix; không coi cả batch là một transaction atomic.

8. Index build hiện dùng transaction + khóa writer, không dùng CONCURRENTLY. Phù hợp proposal dev hiện ledger0/job24 nhưng lock có thể đợi writer khác. Nếu DB tăng lớn hoặc không freeze được, dừng và nhờ DBA thiết kế CREATE INDEX CONCURRENTLY riêng (ngoài transaction, có kiểm tra invalid index/retry); không tự chuyển cách chạy.
9. Giữ Flyway=false, ddl-auto=validate; không sửa flyway_schema_history để “đánh dấu đã chạy”. Lưu change record/hash/before-after ngoài DB theo quy trình team. Startup validate và GET read-only smoke do team thực hiện sau apply; chưa có bước này trên DB chung trong2C. Mở writer theo từng bước sau khi 6 job RUNNING và catalog response được review. Không chạy optimization tính phí như một smoke test tự động.

## 8. Rollback bảo toàn dữ liệu

- Trước COMMIT hoặc khi script lỗi: transaction tự rollback lúc client đóng; psql ON_ERROR_STOP ngăn tiếp tục. Không có DML sửa legacy row trong02/03.
- Sau COMMIT: ưu tiên forward-fix và rollback ứng dụng tương thích, giữ CHECK mở rộng/unique index. Không restore full dump lên DB chung đang có write mới.
- Muốn hẹp CHECK về cũ: freeze/drain, đếm job có algorithm_name không thuộc EP_DBLF/GENETIC_ALGORITHM. Nếu có bất kỳ EP_DBLF_GA/EP_DBLF_GA_AI nào thì **không hẹp CHECK**, không delete/đổi algorithm để ép rollback. Chỉ khi không có mới DBA chuẩn bị transaction thay CHECK bằng đúng DDL before, review riêng.
- Không drop UUID unique có sẵn. Partial USAGE index nên giữ bảo vệ; chỉ cân nhắc drop index do đúng release tạo sau khi review dependencies và bảo đảm writer đã dừng. Không drop index tương đương đã có trước release. Chưa cung cấp script DROP tự chạy vì phải căn cứ baseline thật.
- Catalog chỉ rollback15000→10000 khi before snapshot đúng10000, change log chứng minh04 đã đổi row đó và không có thay đổi nghiệp vụ tiếp theo. Nếu before đã15000/no-op thì không rollback về10000. Chỉ sửa catalog bằng điều kiện identity/cycle/algorithm/value đã review; không trừ lại balance, xóa grant hay kích hoạt lại subscription. Activation/grant đã xảy ra sau04 cần reconciliation nghiệp vụ riêng, không tự đảo ledger.

## 9. Chưa được xác minh

- OS/container/CLI environment override và effective config của process đang chạy; chỉ source + `.env` đã kiểm tra.
- Nội dung thực tế flyway_schema_history, checksum/baseline; không validate/repair/migrate history. Source trùng version là blocker cho việc bật Flyway trong nhiệm vụ tương lai.
- Full clone/backup restore, toàn bộ startup Hibernate validate sau apply, load/lock duration trên shared DB; chưa chạy DDL ở đó.
- Nguồn gốc/liveness/chính sách xử lý6 RUNNING và billing của các job legacy; không tự suy diễn/cấp bù/thu bù.
- GA/AI readiness, end-to-end tính phí/refund, quota hiệu lực theo kỳ và historical meaning của creditsGranted.

Dừng ở Bước2C. Tất cả DDL/catalog SQL vẫn là proposal cần người có trách nhiệm áp dụng sau review.
