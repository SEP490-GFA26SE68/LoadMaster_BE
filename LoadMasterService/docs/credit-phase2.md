# Giai đoạn 2 — Credit, Subscription và Optimization Job

Đã trực tiếp sửa Spring Boot và phần điều phối job Python. Không sửa thuật toán EP-DBLF/GA/AI, frontend, chính sách refund/NO_SOLUTION/PARTIAL, cấu hình bảo mật Giai đoạn 1 hay cấu hình Flyway. Không gọi API trừ/hoàn credit thật, không thay đổi số dư thật, không commit/push.

## 1. INITIAL trước và sau

Trước: controller Spring kiểm tra trip rồi gửi trip_id/objective/time_limit_sec; Python tạo UUID/job, lấy company/tier từ request và runner chỉ gọi deduct khi company_id không null. Vì Spring không gửi company, có đường chạy không bị tính phí. Python cũng là nơi quyết định tier trong quá trình tạo job.

Sau:

1. POST /api/trips/{tripId}/optimize-loading vẫn yêu cầu DISPATCHER và trả HTTP 202 với data.job_id. Spring lấy company từ tài khoản đã xác thực và trip trong database. JSON company/tier/cost từ client không được dùng.
2. OptimizationAdmissionService.reserve() khóa row company ổn định. Kiểm tra sở hữu trip, subscription ACTIVE, started_at không ở tương lai, expires_at còn hiệu lực thực tế; thiếu mốc thời gian thì từ chối. Plan phải active và algorithm tier phải tương ứng BASIC/PRO/ULTIMATE.
3. Spring chọn thuật toán EP_DBLF / EP_DBLF_GA / EP_DBLF_GA_AI từ plan phía server và lưu snapshot company/subscription/algorithm trên job. Không triển khai hay thay đổi GA/AI; việc chọn tier không chứng minh module GA/AI đã sẵn sàng.
4. Tạo job PENDING, trừ 2 credits và ghi đúng một USAGE có reference bằng job_uuid trong cùng transaction. Flush/commit lỗi ở bất kỳ bước nào đều rollback job, account và ledger. Không có HTTP call trong transaction này.
5. Sau commit, OptimizationSubmissionService mới gửi trip_id/job_uuid sang API Python hiện có. Python không tạo thêm job, không nhận quyết định giá/tier từ payload. Nó đọc lại job, trip/company và USAGE chưa refund từ nguồn Spring.
6. Router Python chỉ lên lịch nếu còn PENDING; worker dùng UPDATE ... WHERE status='PENDING' để nhận quyền chạy RUNNING nguyên tử. Nhiều request có thể tạo task trùng, nhưng chỉ một task vượt qua bước nhận quyền chạy solver. Lỗi scheduling giữ job PENDING để retry.
7. Giữ nguyên solve → persistence → handle_result/final validation và nhánh refund ngoại lệ có sẵn cho job đã được xác nhận billing. Python không gọi deduct lần hai và không ghi balance database.

Thiếu company, subscription hết hạn/không hợp lệ hoặc thiếu credits không thể đi tiếp đến solver. Internal credit endpoints vẫn dùng guard service JWT Giai đoạn 1; deduct hiện yêu cầu job server đã tồn tại, company khớp và lấy cost từ parent_plan_id, không nhận giá do caller tùy chọn.

## 2. Idempotency, khóa và transaction

- Client gửi header Idempotency-Key cho mỗi hành động tối ưu; giữ nguyên key khi retry cùng hành động, tạo key mới cho lần bấm tối ưu độc lập. Key được scope theo company và chuyển thành UUID ổn định trong job_uuid đã có unique constraint; không tạo bảng idempotency thứ hai.
- Cùng key/cùng input trả lại cùng job; cùng key nhưng trip, parent, objective, time limit hoặc seed khác bị 409. Thay subscription sau đó không ghi đè snapshot của action đã được nhận.
- Header được giữ optional để tương thích client cũ: không gửi key thì server tạo key mới và echo ở response. **Không bảo đảm nhận diện retry nếu caller không gửi/giữ key**, đặc biệt khi mất response đầu tiên. Frontend chưa được sửa; cần team tích hợp header để có retry an toàn từ UI.
- Thứ tự khóa deduct: company → job → (subscription khi cần khoản trừ mới) → account. Row company giúp tuần tự hóa cả trường hợp chưa có credit account. Query USAGE nằm sau khóa; retry sau commit không trừ lại, kể cả caller đã mất response. Account không đủ tiền thì không thể âm.
- Job/USAGE/account dùng transaction Spring thật. Không bắt và nuốt lỗi commit trong admission. Lỗi HTTP sau commit không được coi là rollback billing.
- USAGE reference là job_uuid xuyên suốt Spring/Python/refund. API deduct cũ và mới dùng cùng CreditServiceImpl, không thêm luồng tính phí thứ hai.

Response vẫn chứa data.job_id; bổ sung data.dispatch_status:

| Giá trị | Ý nghĩa |
|---|---|
| ACCEPTED | Python trả HTTP thành công cho dispatch; không có nghĩa job COMPLETED |
| UNKNOWN | Lỗi truyền thông/khởi chạy không xác định; job đã được reserve bền vững, retry cùng key và kiểm tra status |
| ALREADY_ACCEPTED | Action đã tồn tại và không còn PENDING; đọc status thực tế, không suy ra tối ưu thành công |

Không refund tự động chỉ vì mất HTTP response: Python có thể đã nhận hoặc đang chạy. PENDING có thể được gửi lại bằng cùng key mà không trừ thêm. Đây là cơ chế khôi phục bằng retry, chưa phải durable queue/outbox tự động. Nếu worker chết sau khi chuyển RUNNING, cần đối soát vận hành; không tự chạy lại hoặc tự refund khi chưa xác định kết quả.

## 3. RERUN và giới hạn triển khai

Trước: Spring gọi /api/v1/optimize/rerun không tồn tại trong Python; client bắt exception và chế ra kết quả từ pinned placements. Nhánh này có thể trả thành công giả.

Sau: endpoint rerun vẫn kiểm tra DISPATCHER, company sở hữu plan và subscription. Vì Python chưa có pinned-rerun, trả 503 rõ ràng trước khi tạo job/trừ credit/gọi engine. OptimizationEngineClientImpl cũng fail closed; không còn fallback placements.

Admission và CreditService có phân loại server-side: không parent = INITIAL 2 credits; parent plan hợp lệ cùng trip/company = RERUN 1 credit. Test transaction xác nhận khoản trừ 1 credit cho action có parent. **API rerun thực tế chưa chạy solver và chưa thu 1 credit**, chờ hợp đồng pinned-rerun được triển khai ở giai đoạn khác. Không triển khai pinned-placement, GA hoặc AI ở đây.

## 4. Subscription catalog

SubscriptionPlanInitializer sửa PRO_MONTHLY.monthlyCredits 10000 → 15000; BASIC 3000 và ULTIMATE 30000 giữ nguyên. Initializer chỉ tạo plan chưa tồn tại, không tự sửa catalog cũ hay cộng bù số dư. Nếu catalog hiện có vẫn 10000, team phải review thời điểm hiệu lực và áp dụng chỉnh catalog riêng; SQL đề xuất được chú thích trong script, chưa chạy.

Giữ quy ước LocalDateTime hiện tại của company_subscriptions; kiểm tra thời hạn bằng thời gian thực tế, không chỉ status. Không tự diễn giải chuỗi cũ là UTC. Team cần xác nhận timezone legacy và cấu hình timezone JVM thống nhất. Riêng optimization_jobs.created_at đang là timestamp without time zone và trước đây Python ghi datetime.utcnow; Spring tiếp tục ghi UTC dạng naive vào chính cột này, không ALTER kiểu timestamp.

## 5. Schema và migration chỉ để review

Đã đọc riêng metadata optimization_jobs bằng transaction READ ONLY trên database cấu hình hiện tại; không đọc hoặc cập nhật số dư. Xác nhận các cột company_id/subscription_tier/algorithm_tier/parent_plan_id/seed đã có, job_uuid có UNIQUE, created_at là timestamp without time zone nullable.

Constraint hiện có optimization_jobs_algorithm_name_check chỉ chấp nhận EP_DBLF và GENETIC_ALGORITHM. Nó sẽ từ chối EP_DBLF_GA và EP_DBLF_GA_AI. Cần review/apply thay enum CHECK trước khi dùng PRO/ULTIMATE; nếu chưa sửa, insert thất bại và transaction rollback, không mất credit.

Hai file mới:

- docs/sql/phase2-credit-preflight.sql: chỉ SELECT metadata, duplicate/null references, UUID, constraint và catalog.
- docs/sql/proposed-phase2-credit-migration.sql: script riêng ngoài thư mục auto Flyway; kiểm tra duplicate trước, thêm cột còn thiếu nếu schema khác, unique index job UUID/USAGE reference và thay đúng CHECK enum đã khảo sát. Nó abort nếu định nghĩa constraint khác bản đã review. Không backfill/ủy quyền job cũ, không sửa balance, không đổi timezone hay xóa ledger.

Script đề xuất đã chạy thành công **chỉ trên container PostgreSQL test dùng một lần**, xác nhận giữ nguyên job legacy và balance fixture, rồi cho phép job PRO. Chưa thực thi trên database chung. Cần kiểm tra tên/định nghĩa index có sẵn, duplicates, dung lượng bảng/khóa khi tạo index và backup trước khi áp dụng thật. Dữ liệu ledger không hợp lệ phải được team đối soát, không xóa tự động để qua migration.

Lịch sử migration có version trùng V13/V14/V15. Không sửa history/checksum, không bật Flyway, không khẳng định đã chạy được toàn bộ lịch sử từ schema trống. Team cần giải quyết history riêng trước khi gắn script này vào Flyway có version chính thức.

## 6. Kiểm thử thực tế

- Maven offline compile/test thành công, **112 tests, 0 failures, 0 errors, 0 skipped**.
- Trong đó **23 tests trên PostgreSQL 17 container riêng**, dùng Spring proxy, JPA repository và transaction thật. Không nạp application profile, startup initializer hay migration ứng dụng.
- Python: **78 passed**, gồm 15 test contract/dispatch mới và 63 regression load balance/obstacle. Các client credit/refund được mock; solver regression chạy offline. Không thay code thuật toán/final validation.
- Maven offline package -DskipTests thành công sau lượt test. git diff --check thành công.

| Nhóm test Spring | Số test |
|---|---:|
| OptimizationCreditPostgresTest | 23 |
| OptimizationSubmissionTest | 4 |
| OptimizationSubmissionSecurityTest | 5 |
| CreditServiceTest | 12 |
| CreditInternalControllerTest | 27 |
| LoadPlanSecurityTest | 14 |
| PaymentSecurityTest | 5 |
| MockPaymentPolicyTest | 10 |
| PaymentWebhookControllerTest | 3 |
| PaymentCallbackHandlerTest | 7 |
| VnPayClientTest | 2 |

Coverage theo yêu cầu:

1. INITIAL -2: PostgreSQL kiểm tra balance, USAGE.amount và reference/job liên kết.
2. RERUN -1: PostgreSQL kiểm tra action có parent plan; API unsupported trả 503 không ghi/trừ.
3. Thiếu credit: 402 ở HTTP, không gọi Python; PostgreSQL rollback job/ledger/account.
4. Subscription hết hạn dù ACTIVE, chưa bắt đầu, thiếu subscription: từ chối trước dispatch.
5. Khác company: initial/rerun/pin/unpin/compare đều có test phù hợp.
6. Thiếu company: user, trip hoặc Python job thiếu company đều bị chặn.
7. Retry cùng key, payload conflict, hai key khác nhau: số job và USAGE đúng.
8. Concurrency thật: cùng key, lần deduct đầu cùng reference, retry reference đã commit; chỉ một khoản trừ. Hai job tranh phần tiền chỉ đủ một action không làm âm balance. Có test account chưa tồn tại.
9. Lỗi insert job hoặc ghi ledger: rollback không mất credit.
10. Deduct commit nhưng response mất: retry giữ nguyên job/usage/balance; dispatch lỗi trả reference bền vững và UNKNOWN.
11. Python không deduct lần hai; task trùng không gọi solver thêm; lỗi scheduling vẫn giữ PENDING để retry.
12. Giữ final-validation pipeline, COG/axle/stacking/collision/unload/PARTIAL/timeout/obstacle qua regression có sẵn.
13. Security filters thật ở endpoint INITIAL và regression bảo mật Giai đoạn 1 tiếp tục được chạy; không bỏ role/audience để chữa lỗi.

Đối chiếu nhóm test Python legacy riêng (optimization_job_service, optimization_controller, optimization_dtos, async_optimization_runner):

| | Pass | Fail | Error |
|---|---:|---:|---:|
| Baseline trước thay đổi | 9 | 7 | 14 |
| Sau thay đổi | 9 | 7 | 14 |

Đã đối chiếu đúng 21 node ID fail/error, không có node lỗi mới trong nhóm này. Các fixture dùng UUID cho trip BIGINT và token test cũ không đáp ứng xác thực gây lỗi từ baseline. create_job cũ của Python hiện chủ ý bị chặn theo hợp đồng mới; test legacy cần được cập nhật sang admission/dispatch mới khi team dọn test. Không khẳng định toàn bộ test repository đều xanh, không sửa lỗi ngoài phạm vi.

Log tại workspace:

- D:/VNR_game/phase2-java-tests.log
- D:/VNR_game/phase2-python-tests.log
- D:/VNR_game/phase2-python-baseline.log
- D:/VNR_game/phase2-python-legacy-after.log
- D:/VNR_game/phase2-package.log

Lệnh Spring (PowerShell, JAVA_HOME JDK 21):

```powershell
mvn -o '-Dtest=OptimizationCreditPostgresTest,OptimizationSubmissionTest,OptimizationSubmissionSecurityTest,CreditServiceTest,CreditInternalControllerTest,LoadPlanSecurityTest,PaymentSecurityTest,MockPaymentPolicyTest,PaymentWebhookControllerTest,PaymentCallbackHandlerTest,VnPayClientTest' '-Dspring.flyway.enabled=false' '-Dspring.jpa.hibernate.ddl-auto=none' test -q
mvn -o '-DskipTests' '-Dspring.flyway.enabled=false' '-Dspring.jpa.hibernate.ddl-auto=none' package -q
```

Lệnh Python trong OptimizeService:

```powershell
.venv/Scripts/python.exe -m pytest -q tests/test_authorized_job_dispatch.py tests/test_load_balance_search.py tests/test_vehicle_obstacles.py -p no:cacheprovider
```

## 7. Toàn bộ file của Giai đoạn 2 (30)

Các thay đổi timestamp/obstacle/bảo mật có sẵn được giữ nguyên. Danh sách dưới đây chỉ tính file sửa/thêm trong Giai đoạn 2, không bao gồm artifact build hay báo cáo ngoài workspace repo.

- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/repository/company/CompanyRepository.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/repository/optimization/OptimizationJobRepository.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/repository/billing/CompanySubscriptionRepository.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/constant/optimization/OptimizationAlgorithm.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/entity/optimization/OptimizationJob.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/exception/ErrorCode.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/config/SubscriptionPlanInitializer.java`
- Thêm: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/service/optimization/OptimizationEntitlementService.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/service/credit/Impl/CreditServiceImpl.java`
- Thêm: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/service/optimization/OptimizationAdmissionService.java`
- Thêm: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/service/optimization/OptimizationSubmissionService.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/controller/optimization/LoadingOptimizationController.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/service/optimization/Impl/LoadPlanServiceImpl.java`
- Sửa: `LoadMasterService/src/main/java/fu/se184491/loadmaster_be/service/optimization/Impl/OptimizationEngineClientImpl.java`
- Sửa: `OptimizeService/app/dto/request/optimization_request.py`
- Sửa: `OptimizeService/app/entity/trip_model.py`
- Sửa: `OptimizeService/app/entity/optimization/optimization_job.py`
- Thêm: `OptimizeService/app/service/optimize/authorized_job_dispatch.py`
- Sửa: `OptimizeService/app/controller/optimization/optimization_router.py`
- Sửa: `OptimizeService/app/service/optimize/optimization_job_service.py`
- Sửa: `OptimizeService/app/service/optimize/async_optimization_runner.py`
- Sửa: `LoadMasterService/src/test/java/fu/se184491/loadmaster_be/security/LoadPlanSecurityTest.java`
- Sửa: `LoadMasterService/src/test/java/fu/se184491/loadmaster_be/service/credit/CreditServiceTest.java`
- Thêm: `LoadMasterService/src/test/java/fu/se184491/loadmaster_be/service/optimization/OptimizationSubmissionTest.java`
- Thêm: `LoadMasterService/src/test/java/fu/se184491/loadmaster_be/service/optimization/OptimizationCreditPostgresTest.java`
- Thêm: `OptimizeService/tests/test_authorized_job_dispatch.py`
- Thêm: `LoadMasterService/docs/sql/phase2-credit-preflight.sql`
- Thêm: `LoadMasterService/docs/sql/proposed-phase2-credit-migration.sql`
- Thêm: `LoadMasterService/src/test/java/fu/se184491/loadmaster_be/security/OptimizationSubmissionSecurityTest.java`
- Thêm: `LoadMasterService/docs/credit-phase2.md`

## 8. Cần team xác nhận trước triển khai/merge

- Review enum CHECK cho PRO/ULTIMATE và index USAGE; áp dụng thủ công sau preflight/backup. Hiện chưa thay database chung.
- Xác nhận timezone gốc của subscription timestamps; giữ JVM timezone nhất quán.
- Quyết định cập nhật catalog PRO cũ sang 15000 từ kỳ nào; không tự cộng bù balance.
- Web/Mobile cần giữ và gửi Idempotency-Key cho retry; mỗi hành động độc lập dùng key mới. Chưa sửa frontend.
- Triển khai đồng bộ hợp đồng Spring/Python, drain job cũ và chặn admission tạm trong rollout. Python mới đòi job đã được Spring tạo/bill; Spring cũ không còn tương thích với lệnh submit mới. Không rolling hai contract khác nhau mà vẫn cho tạo job.
- PENDING retry được bằng cùng key; RUNNING bị worker chết cần đối soát vận hành. Chưa có durable queue/lease/outbox hoặc tự động khôi phục job treo.
- Rerun pinned chưa được hỗ trợ: 503, không thu tiền. Không coi test chi phí RERUN là bằng chứng API rerun đã hoàn thiện thuật toán.
- Refund transport/retry, quota reconciliation lịch sử, GA/AI implementation và dọn test legacy không được triển khai trong giai đoạn này. NO_SOLUTION/PARTIAL/refund policy không đổi.

Dừng sau Giai đoạn 2.
