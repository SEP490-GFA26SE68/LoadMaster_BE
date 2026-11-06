# LoadMaster — bàn giao Bước 2D.1

Đã kiểm thử migration trên **full logical clone thực tế** của database dev; không dùng schema tổng hợp thay thế kết luận. **Không áp dụng migration/catalog SQL lên dev dùng chung.** Không sửa runtime, thuật toán, frontend, billing/refund/subscription, Flyway, history/checksum/baseline; không commit/push.

## 1. Nguồn, đích và giới hạn quyền truy cập

| Nội dung | Đã xác minh |
| --- | --- |
| Nguồn | **localhost:5432/loadmaster**, PostgreSQL17.11,11.720.371 bytes,50 bảng người dùng |
| Quyền nguồn | CONNECT và SELECT tất cả bảng người dùng; cả preflight, fingerprint và pg_dump dùng READ ONLY |
| Nhận diện nguồn | Kết nối Python và helper pg_dump được đối chiếu **cluster system identifier + database OID + current_database()**; không chỉ so tên host |
| Đích | **127.0.0.1:56577/loadmaster_clone_2d1_107a146f334e** trong container mới **loadmaster-phase2d1-107a146f334e** |
| Nhận diện đích | Cluster system identifier khác nguồn; tên DB khác loadmaster; public schema rỗng trước restore; không reuse volume/database hiện hữu |
| Network | Bridge riêng `loadmaster-2d1-isolated-107a146f334e`, chỉ publish localhost; không phải network egress-deny. Không nạp remote clients vào Boot context; guard nguồn không có foreign server/RLS/event trigger |
| Trạng thái cuối | Container clone đã **stopped**, giữ dữ liệu clone để review; không DROP/TRUNCATE/overwrite database nào |

Mọi đích đều do harness tạo mới với token ngẫu nhiên, không nhận external target URL. Docker từ chối tên container trùng. Restore không có `--clean`, `--create` hoặc thao tác drop database. Không mount/reuse volume nguồn.

Đã dùng pg_dump custom-format **không lọc bảng/schema/section**, exported snapshot nhất quán với transaction READ ONLY dùng tính fingerprint. Restore exit0 với no-owner/no-acl vào clone rỗng. All50table row fingerprints và inventory tên/loại relation, constraint + validated flag, function identity khớp nguồn. Large objects0. Source chỉ có extension plpgsql; không thấy foreign server, RLS trên public hoặc enabled DDL event trigger.

**Giới hạn clone:** không clone cluster globals/roles/password hay ACL/ownership nguồn. Restore dùng owner clone; Boot đọc bằng role clone riêng. Vì thế chưa chứng minh các quyền của deployment role thật. Fingerprint bảng dùng MD5 của JSON từng row, tổng hợp ổn định trong UTC; archive có SHA256. Đó là phép đối chiếu dữ liệu, không phải audit mật mã chống đối thủ. Inventory không so nguyên văn mọi function body; full archive restore thực tế và Hibernate là các kiểm tra bổ sung.

## 2. Preflight dev READ ONLY — trước và sau rehearsal

Snapshot cuối: **2026-11-05 07:35:37.143956+00:00**. Các mục metadata/aggregate dưới đây so before/after đều không đổi:

| Mục | Kết quả dev hiện tại |
| --- | --- |
| Algorithm CHECK | Chỉ EP_DBLF/GENETIC_ALGORITHM; NULL vẫn được CHECK cũ chấp nhận |
| Algorithm data |24job, tất cả EP_DBLF |
| Job UUID | varchar64 nullable, unique `uk2t1ly949hnjlhly02pe9cku30` valid/ready;0 NULL/blank/duplicate |
| USAGE reference |0 invalid/duplicate, **chưa có partial unique index**; idx_credit_reference là non-unique |
| Cột Phase2 | company_id,subscription_tier,algorithm_tier,parent_plan_id,seed đã có đúng kiểu |
| Catalog | BASIC3000, **PRO10000**, ULTIMATE30000; algorithm_tier khớp từng gói |
| PENDING/RUNNING | PENDING0; RUNNING6, cả6 thiếu snapshot và không có USAGE |
| Credit/subscription data | CreditAccount0, CreditTransaction0, CompanySubscription0 trong snapshot full clone |
| Triggers/rules | Không có user trigger/rule trên các bảng Phase2; không có event trigger |
| Flyway | Source config disabled; bảng history được copy trong dump nhưng không sửa hay chạy Flyway |

Không in job UUID, ledger reference, company/user data, token hay balance. Source archive và clone config chứa dữ liệu/credential nằm trong folder ACL riêng, không đưa vào Git hoặc báo cáo công khai.

## 3. Migration trên full clone

**PASS** theo đúng sequence01→02→03→04→05:

1.01preflight chạy trên full clone trước DDL, đối chiếu cùng shape dev.
2.02reuse unique UUID cũ; thêm đúng một `uk_phase2_credit_usage_reference` WHERE type='USAGE'. Không thêm/sửa cột.
3.03đổi đúng algorithm CHECK, giữ EP_DBLF/GENETIC_ALGORITHM/NULL, thêm EP_DBLF_GA/EP_DBLF_GA_AI. Không sửa algorithm/job data.
4.04chỉ đổi PRO_MONTHLY.monthly_credits10000→15000; không activate/grant/refund/deduct.
5.05postflight không lỗi; preflight metadata sau migration xác nhận CHECK mới validated, index USAGE unique valid, unique job_uuid vẫn chỉ dùng bản cũ; catalog3000/15000/30000.

**Bảo toàn dữ liệu:** so fingerprint tất cả50bảng trước/sau chỉ `public.subscription_plans` khác, đúng field đã cho phép. CreditAccount, CreditTransaction, CompanySubscription, optimization_jobs và flyway_schema_history khớp nguyên trạng. Không có bản ghi credit/subscription trong snapshot này, nên PASS bảo toàn cho các bảng đó chỉ kiểm chứng trạng thái rỗng; không khẳng định đã thử đầy đủ ledger/subscription có dữ liệu thật. Kịch bản non-empty của2C là bằng chứng bổ sung riêng, không thay cho hạn chế này.

**Đối chiếu field độc lập READ ONLY:** so hash toàn bộ catalog fields ngoài monthly_credits giữa nguồn và clone, đồng thời so monthly_credits từng plan: chỉ PRO_MONTHLY.monthly_credits khác10000→15000; mọi field khác và hai gói BASIC/ULTIMATE đều khớp. Bằng chứng: `catalog-field-proof.json`. Clone được dừng lại sau lần đọc này.

**Chạy lại02/03/04/05:** PASS no-op, inventory schema và fingerprint dữ liệu không đổi. Không cần sửa bất kỳ script01–05 nào để tương thích full clone.

## 4. Spring Boot/Hibernate và repository

**PASS trước và sau migration**, Java21, Hibernate7,47Entity và41Repository của source Spring hiện tại:

```text
Before: CLONE_VALIDATION_PASS entities=47 repositories=41 jobs=24 accounts=0 transactions=0 subscriptions=0 proMonthlyCredits=10000 schedulers=0 runners=0 remoteClients=0 flyway=false
After:  CLONE_VALIDATION_PASS entities=47 repositories=41 jobs=24 accounts=0 transactions=0 subscriptions=0 proMonthlyCredits=15000 schedulers=0 runners=0 remoteClients=0 flyway=false
```

Đã build source hiện tại bằng Maven offline/package; sau đó compile/run `CloneValidationApplication.java` với đúng target/classes và thư viện trong Boot jar.

Đây là **Spring Boot test context riêng, non-web**, không phải `LoadMasterBeApplication.main()` hoặc production web startup. Config chỉ khai báo DataSource, full EntityManagerFactory, transaction manager và scan toàn bộ Repository. Hibernate dùng `hbm2ddl.auto=validate`; Flywayfalse. App YAML dev bị thay bằng private config location cho lần chạy này, không ghi đè file dev.

Role clone `phase2d_reader` không superuser/createdb/createrole, chỉ CONNECT/USAGE/SELECT; `default_transaction_read_only=on`. Guard Java chỉ nhận URL localhost có tên DB loadmaster_clone_2d1_*, xác minh current_database và READ ONLY trước JPA init. Không có scheduler postprocessor, ApplicationRunner, CommandLineRunner, RestClient hoặc Flyway bean. Không mở web server/controller, không dùng Keycloak/Redis/Python. Source có @EnableScheduling và startup initializers nên cố ý không component-scan main app.

Repository startup và read smoke: OptimizationJob.count24, CreditAccount.count0, CreditTransaction.count0, CompanySubscription.count0; SubscriptionPlan.findByActiveTrue đọc PRO10000 trước và15000 sau. Fingerprint sau Boot không đổi, không thực hiện repository save/lock/deduct/refund/dispatch.

**Chưa xác minh:** full web context/security/OAuth/external integrations, business endpoint E2E, startup initializers ở profile dev và scheduler thực tế. Không nên gọi kết quả này là toàn bộ production startup/E2E pass; phạm vi được chứng minh là full entity/repository Hibernate validate trên full clone với cấu hình an toàn.

## 5. Catalog PRO và creditsGranted

Không có subscription cũ hoặc PRO subscription trong snapshot: count0, active0. Vì vậy chưa thể quan sát thay đổi response của **một hợp đồng cũ thực tế** trên clone.

Source `ContractServiceImpl.mapToResponse()` vẫn lấy creditsGranted trực tiếp từ entity.getPlan().getMonthlyCredits(), không từ lịch sử grant. Repository đã đọc thấy plan10000→15000. Do đó với một hợp đồng tham chiếu PRO, field hiển thị có thể đổi sang15000 dù từng được cấp10000; đây là đánh giá từ code + catalog, **không phải E2E hợp đồng cũ đã được test**. Script không thêm CreditAccount/ledger/subscription. Team phải chốt hiệu lực theo lần mua/gia hạn và ý nghĩa response trước áp dụng04. Không sửa runtime trong2D.1.

## 6. Rollback theo runbook — chỉ trên clone

**PASS:** xác minh không có row dùng algorithm mới trước khi hẹp CHECK; khôi phục đúng CHECK before; chỉ drop partial USAGE index do rehearsal tạo (không đụng UUID unique cũ); đổi PRO15000→10000 vì before snapshot đúng10000. Toàn bộ50table fingerprint và schema-object inventory về đúng trước migration. Không delete ledger/job, không thay algorithm hoặc balance.

**PASS:** thử thay catalog trong transaction rồi ROLLBACK trước commit, data fingerprint trở lại nguyên trạng.

Sau kiểm thử rollback đã áp dụng lại02/03/04/05; clone cuối giữ trạng thái migrated, PRO15000. Container sau đó dừng. Không thử rollback sau khi có job GA/AI mới, activation/grant mới hoặc writer đồng thời. Trường hợp đó phải giữ schema tương thích/forward-fix và đối soát, không dùng kết quả rollback clone này làm lý do xóa/đổi dữ liệu mới.

## 7. BLOCKER/WARNING trước triển khai dev dùng chung

- **BLOCKER vận hành:**6RUNNING thiếu snapshot/USAGE chưa được phân loại; status không chứng minh worker còn sống hay đã chết. Không reset/rerun/backfill/thu bù/refund tự động. Cần owner đối soát, kế hoạch freeze/drain và quyết định được duyệt trước mở admission.
- **BLOCKER business cho04:** chốt thời điểm PRO15000 có hiệu lực và việc creditsGranted đọc mutable catalog. Hiện không có hợp đồng cũ để kiểm thử thực tế trường hợp này.
- **WARNING quyền/config:** clone không sao chép ACL/ownership/cluster globals; quyền DDL của deployment role, OS/container/CLI overrides và startup web context dev chưa được chứng minh.
- **WARNING load:** nguồn nhỏ/ledger rỗng, chưa đo lock/index build với ledger lớn hoặc concurrent writer. Giữ lock_timeout/statement_timeout; không tự đổi sang CONCURRENTLY.
- **WARNING Flyway:** giữ disabled; source có trùng V13/V14/V15, không repair/baseline/update checksum/history. Rehearsal không giải quyết việc bật Flyway tương lai.
- **Không có blocker schema/Hibernate mới** từ sequence01–05 trên full clone đã kiểm tra. Điều đó không phải phê duyệt tự triển khai.

## 8. Sequence thủ công áp dụng dev — CHƯA THỰC HIỆN

1.Review kết quả và hash scripts; đối chiếu sourcehost5432/loadmaster theo kênh vận hành. Chọn người thao tác/roleDDL, thời điểm bảo trì và catalog effective cycle. Không dùng connection clone hoặc password trong log công khai.
2.Đối soát6RUNNING, freeze admission/rerun, drain worker thật; phối hợp các writer credit/payment/activation/renewal. Không bypass security hoặc kill/reset job chỉ theo status.
3.Chạy01preflight READ ONLY lại sát thời điểm apply. Dừng nếu schema/index/check/catalog/data/trigger/rule khác snapshot, reference invalid/duplicate, hoặc có job mới chưa phân loại. Không sửa/xóa data để vượt preflight.
4.Backup mới full custom-format + schema-only, lưu ACL/encryption theo quy trình. Kiểm tra hash/list và restore thử vào clone mới. Không dùng archive rehearsal cũ làm backup cho lần apply sau nếu nguồn đã đổi. Không overwrite/clean dev.
5.DBA chạy đúng02 rồi03 với psql ON_ERROR_STOP=1.04là quyết định riêng: chỉ chạy khi business đã duyệt. Mỗi file có transaction riêng; bất kỳ exit khác0 thì dừng toàn sequence, không auto-retry, không giả định cảbatch atomic.
6.Chạy05 và01; đối chiếu dữ liệu ngoài catalog, credit/subscription/Flyway history và metadata trước/sau. Đọc đủ3catalog rows; catalog PRO chỉ phải15000 nếu04đã được duyệt/chạy. Không bật Flyway để ghi nhận thủ công.
7.Dùng profile/config kiểm chứng riêng như rehearsal cho Hibernate/repository read smoke; kiểm tra deployment role thật. Khi bật main dev app, cần cấu hình/điều phối rõ scheduler và startup runners — không chỉ đặt spring.task.scheduling.enabled=false rồi giả định @EnableScheduling đã tắt. Chưa có cơ chế bật/tắt an toàn toàn bộ runner được triển khai trong nhiệm vụ này.
8.Mở writer từng bước theo owner; GET read-only smoke. Không dùng optimization tính phí/deduct/refund thật làm smoke tự động. Nếu cần rollback, theo điều kiện before snapshot ở runbook2C; có job thuật toán mới thì không hẹp CHECK. Grant đã phát sinh sau04 cần reconciliation riêng, không tự trừ lại balance.

Ví dụ apply vẫn dùng các command psql/backup của `../phase2c/README.md`; không có auto-deploy script trong2D.1.

## 9. Bằng chứng, artifacts và tái chạy

- `D:/VNR_game/phase2d1/full-clone-result.json`: aggregate pre/post và các assertion.
- `D:/VNR_game/phase2d1/source-before.json`, `source-after.json`: READ ONLY source snapshots, metadata/aggregate đều không đổi.
- `D:/VNR_game/phase2d1/handoff-summary.json`: kết quả gọn, không credential.
- `D:/VNR_game/phase2d1/rehearsal.log`, `spring-build.log`: execution log không data dump.
- Archive custom-format, full Boot logs và clone config chỉ trong **`D:\VNR_game\phase2d1\private_107a146f334e`**, ACL cấp cho account chạy và SYSTEM; không copy vào repository. Không in credential hoặc cấp link tải archive nhạy cảm trong báo cáo.
- Container `loadmaster-phase2d1-107a146f334e` đã dừng, giữ clone; port trên là port khi rehearsal. Nếu khởi động lại phải kiểm tra mapping port và identity, không dùng lại config cũ một cách máy móc. Hai lượt setup trước đã dừng trước restore (ACL/cổng network); không database hiện hữu nào bị overwrite; clone rỗng từ lượt cổng lỗi cũng đã stopped.

Để tái chạy trên máy này: build Spring offline với Java21 trước, rồi dùng Python venv OptimizeService chạy `full_clone_rehearsal.py`. Harness đọc source `.env`, luôn tạo clone mới, không nhận target URL ngoài. Source/WORK/JDK path hiện được cấu hình cho máy này; review khi mang sang máy khác. Boot source ở cùng thư mục harness; output mặc định D:/VNR_game/phase2d1, private subfolder mới có ACL. Một lần chạy mới cần quyền đọc full source, Docker, Java/Javac và được team cho phép copy dữ liệu. Chưa tạo cleanup tự xóa database/volume; quản lý retention các archive/clone qua owner.

Files2D.1: test-only `CloneValidationApplication.java`, `full_clone_rehearsal.py` , `verify_catalog_scope.py` và runbook này dưới docs/sql/phase2d1; thêm pointer lịch sử ở README2C. Không đổi01–05 vì đã tương thích. Không chạy lại regression nghiệp vụ/FE không liên quan; Maven package pass và full-clone rehearsal trên source hiện tại là bằng chứng của bước này.

Dừng tại Bước2D.1. Không triển khai migration lên dev dùng chung.
