# Kế hoạch triển khai mới — 2 người

> Cập nhật: 2026-10-27  
> Mục tiêu: hoàn thiện MVP backend bằng các lát cắt nghiệp vụ nhỏ, mỗi việc tối đa 0,5–1 ngày công.  
> Tài liệu này thay thế cách phân công WP lớn ở `IMPLEMENTATION_PLAN.md`; kế hoạch cũ được giữ làm lịch sử/bằng chứng.

## 1. Phân vai và nguyên tắc

| Vai trò | Sở hữu chính | Phối hợp |
|---|---|---|
| **N1 — Platform & Submission** | Gateway/common, Identity, Submission, MinIO/RabbitMQ/PDF, Compose | Review mọi internal contract và thay đổi bảo mật. |
| **N2 — Exam & Result** | Exam-Grading, Result-Review, frontend demo smoke, acceptance/E2E | Review các luồng dữ liệu nhạy cảm và contract liên service. |

- `N1+N2` là pair tối đa nửa ngày để chốt contract; không để một người chờ implementation của người kia.
- Một ticket chỉ hoàn tất khi có code review, test, Flyway (nếu đổi dữ liệu), OpenAPI/contract (nếu đổi API), và cấu hình/tài liệu liên quan.
- Không truy vấn database service khác. Internal API phải có HMAC, caller allowlist, version/schema test và không lộ PII, phách, ORIGINAL, token hoặc presigned URL.
- Merge ticket nhỏ mỗi ngày; cuối mỗi đợt chạy demo qua Gateway, không gọi trực tiếp service/DB/MinIO.

## 2. Baseline hiện tại

Đã có scaffold Gradle, Compose/Gateway/MinIO, HMAC/idempotency/outbox primitive, login/JWKS, một phần schema Identity, create exam/question/rubric, internal submission status, topology RabbitMQ, schema assignment/review/final result, council decision, export CSV và frontend demo. Các phần này chưa được coi là xong cho đến khi đi qua test/acceptance bên dưới.

### Tiến độ đã xác nhận từ mã nguồn và nhật ký cũ

Ký hiệu: `[x]` = đã có và đủ phạm vi của mục nhỏ nêu dưới đây; `[~]` = đã có một phần nhưng **chưa** được tính hoàn thành ticket/đợt; `[ ]` = chưa bắt đầu hoặc chưa có bằng chứng.

**Đợt 0**

- [x] Scaffold Gradle multi-module, Gradle Wrapper, Dockerfile/Compose cho Gateway, 4 service, PostgreSQL, RabbitMQ và MinIO.
- [x] `common`: HMAC, replay guard, idempotency, cursor, audit redaction và primitive outbox có mặt; một phần có unit test.
- [x] Gateway chặn `/internal/**`, có storage allowlist, lọc header nhạy cảm, correlation ID và rate limit.
- [x] Đã từng chạy thành công full Gradle test, Compose config và smoke Compose (nhật ký 2026-10-13).
- [~] Storage GET qua Gateway, including Range/URL hết hạn, đã được smoke; POST 50 MiB và full automation AT-N01–N12 còn thiếu.
- [ ] Bốn internal contract ở F0-06 chưa được chốt/kiểm thử trọn bộ.

**Đợt 1**

- [x] Identity có login, `/users/me`, đổi/reset password, tạo user, JWKS và bootstrap admin.
- [x] Candidate search và import **CSV** từng dòng, có trả danh sách lỗi từng dòng.
- [x] Exam có create, thêm question, tạo rubric draft và activate rubric cần `confirmed=true`.
- [~] Login/account lockout, roster import XLSX, user CRUD đầy đủ, binding/pseudonym, exam update/close, validate rubric điểm và grader registry chưa đạt ticket tương ứng.

**Đợt 2**

- [x] Flyway đã có bảng lõi PaperTemplate, Submission, ExamFile, ProcessingJob và state enum; internal status transition có HMAC/caller allowlist cơ bản.
- [~] Thiếu init/complete upload, outbox VALIDATE, SSE, cleanup và replace-file hoàn chỉnh; do đó chưa tick S2-01–S2-08.

**Đợt 3**

- [x] Khai báo exchange `submission.processing`, hai durable queue `validate`/`anonymize` và hai DLQ riêng.
- [~] Consumer hiện là khung; chưa có PDF validation, PageMasker, retry/reconciler, pseudonym và preview nên chưa tick P3-01–P3-08.

**Đợt 4**

- [x] Migration đã khai báo bảng lõi assignment/attempt/question-score và unique constraint assignment.
- [ ] Chưa có assignment, grading attempt, score/confirm và outbox hand-off hoàn chỉnh.

**Đợt 5**

- [x] Migration đã có Review, CouncilDecision, FinalResult và index chỉ một review mở; có read projection, council decision endpoint và export CSV read-only.
- [~] Chưa có evaluate/consume attempt, full workflow review, finalize saga, ghép phách hay kiểm thử quyền đầy đủ; không tick R5-01–R5-08.

**Đợt 6**

- [ ] Remark, retention purge liên service, CI acceptance, backup/restore drill và release checklist chưa có bằng chứng hoàn tất.

## 3. Cổng theo đợt

| Đợt (5 ngày) | Demo bắt buộc | Acceptance/cổng chính |
|---|---|---|
| 0 | Môi trường lặp lại được và storage qua Gateway | S0/WP0, AT-B02, AT-N01–N12, AT-A06/A07 |
| 1 | EO tạo exam/rubric, import roster, đăng ký grader | AT-K01–K14, AT-C01/C02, AT-D01–D06 |
| 2 | EO upload PDF và thấy tiến trình submission | AT-B01–B06, AT-I01–I04, AT-L01–L05/L17/L18 |
| 3 | PDF được validate, ẩn danh, sẵn sàng chấm | AT-B07–B09, AT-C03–C16, AT-P01–P15 |
| 4 | GRADER được giao bài, chấm và confirm | AT-E01–E06, AT-A02/A03/A09/A10, AT-L06/L10/L11 |
| 5 | Review, Council quyết định, EO finalize/export | AT-E07–E09, AT-F01–F18, AT-G01–G09 |
| 6 | Remark, purge và release candidate | AT-F19–F26, AT-H01–H12, AT-A04/A11, mục J |

## 4. Backlog nhỏ

### Đợt 0 — Nền tảng và contract

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| F0-01 | N1 | Chạy `gradlew.bat test --no-daemon`, sửa test/config không xác định môi trường. | — | Tất cả module xanh, lưu lệnh chạy. |
| F0-02 | N1 | Tự động hóa presigned POST 50 MiB qua `/storage/original`. | F0-01 | AT-B02; key/size/expiry sai bị chặn. |
| F0-03 | N1 | Hoàn tất Gateway allowlist, rate limit và test. | F0-02 | AT-N01–N12, AT-A06/A07. |
| F0-04 | N2 | Smoke Compose: health Gateway và 4 service. | F0-01 | Một lệnh có trong README. |
| F0-05 | N2 | Chuẩn hóa lỗi/CORS/request ID trên public route hiện có. | F0-01 | Contract test 401/403/404, redaction. |
| F0-06 | N1+N2 | Chốt schema 4 internal API: user lookup, exam projection, submission status, attempt confirmed. | F0-03 | OpenAPI/schema, HMAC/allowlist/replay test. |

### Đợt 1 — Identity và cấu hình exam

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| I1-01 | N1 | Migration/test tài khoản, bootstrap EO, login lockout. | F0-06 | AT-K01–K05/K12–K14. |
| I1-02 | N1 | CRUD user, grader profile, bảo vệ EO cuối cùng. | I1-01 | AT-K06–K10. |
| I1-03 | N1 | Roster import CSV theo từng dòng và XLSX adapter. | I1-01 | AT-C01/C02. |
| I1-04 | N1 | Binding idempotent, release/purge internal contract. | I1-03 | AT-O07/O08. |
| E1-01 | N2 | Create/update/activate/close exam, invariant trạng thái. | F0-06 | AT-D01/D02. |
| E1-02 | N2 | Validate points question/criterion, rubric version/activate. | E1-01 | AT-D03/D04/D06. |
| E1-03 | N2 | Grader registry qua Identity lookup. | I1-02 | AT-K11. |
| E1-04 | N2 | DOCX rubric import chỉ tạo draft/gợi ý. | E1-02 | Test merge/table unsupported. |

**Demo:** EO đăng nhập → tạo exam/rubric active → import roster → đăng ký grader.

### Đợt 2 — Upload và vòng đời submission

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| S2-01 | N1 | Migration PaperTemplate, Submission, ExamFile, ProcessingJob/Event. | I1-04/E1-02 | Testcontainers; Submission DB không có PII/phách. |
| S2-02 | N1 | PaperTemplate create/version/mask validation. | S2-01 | Template đã dùng không thể sửa. |
| S2-03 | N1 | `init-upload`: ACTIVE exam, binding, presigned POST key/version. | S2-02/F0-02 | AT-B01/B02, idempotency key. |
| S2-04 | N1 | `complete-upload`: HEAD size, transition, outbox VALIDATE. | S2-03 | AT-B03/B04, duplicate-safe. |
| S2-05 | N1 | Abandoned/orphan cleanup và replace-file guard. | S2-04 | AT-L01–L05/L17/L18. |
| S2-06 | N1 | SSE sequence, Last-Event-ID, snapshot fallback. | S2-04 | AT-I01–I04. |
| S2-07 | N2 | Contract test exam projection/submission status cho worker. | E1-01/F0-06 | Reject version/signature/replay lỗi. |
| S2-08 | N2 | UI demo upload + SSE qua Gateway. | S2-03 | Smoke không dùng direct service URL. |

### Đợt 3 — Validate, anonymize, preview

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| P3-01 | N1 | Retry/DLQ/reconciler VALIDATE và ANONYMIZE. | S2-04 | AT-M01/M02, duplicate-safe. |
| P3-02 | N1 | Worker VALIDATE: MIME/PDF/page/dimension/checksum/timeout. | P3-01 | AT-B07, C03–C06. |
| P3-03 | N1 | Sinh pseudonym sau validate; message anonymize không PII. | P3-02/I1-04 | AT-C07/C08. |
| P3-04 | N1 | PageMasker rasterize, lossless white mask, strip metadata. | P3-02 | AT-C09–C16 pixel/lossless. |
| P3-05 | N1 | Anonymize worker, version/force guard, READY_FOR_GRADING. | P3-04 | AT-B08/B09, L08/L14/L15. |
| P3-06 | N1 | Submission/template preview dùng chung PageMasker. | P3-04 | AT-P01–P15. |
| P3-07 | N2 | Test consumer projection/retry visibility đúng role. | P3-01 | Không lộ ORIGINAL/PII. |
| P3-08 | N2 | Chuẩn bị schema assignment/attempt + test. | E1-03 | Chưa mở public endpoint. |

**Demo:** PDF upload → VALIDATE → anonymized PDF → `READY_FOR_GRADING`; EO thấy preview, GRADER không thấy ORIGINAL.

### Đợt 4 — Assignment và grading

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| G4-01 | N2 | Bulk assignment atomic, cross-grading, round-robin. | P3-05/P3-08 | AT-E01/E02/E02b. |
| G4-02 | N2 | Assignment list chỉ của owner/pseudonym. | G4-01 | AT-A02/A03/A09. |
| G4-03 | N2 | Attempt context rubric + anonymized URL. | G4-02 | AT-E03, privacy test. |
| G4-04 | N2 | Score/criteria BigDecimal, validate range/tổng. | G4-03 | AT-E04/E05. |
| G4-05 | N2 | Confirm atomic/idempotent + outbox snapshot. | G4-04 | AT-E06/M07. |
| G4-06 | N2 | Discard open work + attempt summary. | G4-04 | AT-L06/L10/L11. |
| G4-07 | N1 | Internal anonymized access projection cho grader/review. | P3-05 | Role/service authorization test. |
| G4-08 | N1 | Consumer/replay test attempt-confirmed. | G4-05 | Outbox redelivery + signed request test. |

### Đợt 5 — Review, Council, finalize

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| R5-01 | N2 | Consume attempt-confirmed, evaluate single/cross, HALF_UP. | G4-05 | AT-E07/E08. |
| R5-02 | N2 | Create/merge review; unique review mở. | R5-01 | AT-E09, F01–F04, O10–O13. |
| R5-03 | N2 | EO/COUNCIL review projections không identity/original. | R5-02/G4-07 | AT-A05/A12/A14. |
| R5-04 | N2 | Council decision optimistic lock theo reason/action matrix. | R5-03 | AT-F05–F14. |
| R5-05 | N1 | EO authorized retry/replace/single-review Submission actions. | R5-04/S2-05 | AT-F14b/F14c, L12/L13. |
| R5-06 | N1 | Anonymization issue/reprocess template mới, guard sau giao chấm. | R5-05 | AT-Q01–Q13. |
| R5-07 | N2 | Finalize saga + FinalResult version đầu, idempotent. | R5-02 | AT-F15–F18. |
| R5-08 | N2 | Ghép phách EO-only, export CSV/XLSX, block premature export. | R5-07/I1-04 | AT-G01–G09. |

**Demo:** hai lượt chấm → score difference → Council quyết định → EO finalize → export.

### Đợt 6 — Remark, retention và release

| ID | Owner | Việc | Phụ thuộc | Bàn giao |
|---|---|---|---|---|
| L6-01 | N2 | Remark/withdraw/regrade, version mới giữ deadline cũ. | R5-07 | AT-F19–F26. |
| L6-02 | N2 | Retention scheduler/hold/PurgeReceipt orchestration. | L6-01 | AT-H01–H05. |
| L6-03 | N1 | Purge Identity/Submission: mapping, binding, all objects/versions. | L6-02 | AT-H06–H09, rerun idempotent. |
| L6-04 | N2 | Purge Exam/Result, giữ FinalResult core đúng policy. | L6-02 | AT-H10–H12/L16. |
| L6-05 | N1 | Authorization matrix + internal contract/replay suite. | All | AT-A04/A11. |
| L6-06 | N2 | CI build/migration/unit/integration/contract và traceability AT. | All | Pipeline từ checkout sạch. |
| L6-07 | N1+N2 | Load/recovery smoke, backup/restore drill, runbook/release checklist. | L6-03–L6-06 | Mục J, không còn P0/P1. |

## 5. Definition of Done và kiểm soát rủi ro

**Definition of Done cho mọi ticket**

1. Người còn lại review; không thêm truy vấn DB xuyên service.
2. `./gradlew.bat :<module>:test --no-daemon` xanh; việc integration có Testcontainers/Compose test phù hợp.
3. Migration + OpenAPI/contract + README/.env.example được cập nhật khi có tác động.
4. Test role EO/GRADER/COUNCIL và redaction chứng minh không rò password, JWT, presigned URL, identity, phách hay điểm sai role.

**Cuối mỗi đợt:** toàn bộ `./gradlew.bat test --no-daemon` xanh, `docker compose --env-file .env.example -f deploy/docker-compose.yml config --quiet` xanh, smoke Compose thực tế xanh và AT của đợt được ghi trong PR.

| Rủi ro | Cách xử lý |
|---|---|
| Contract đổi làm người kia chờ | Chốt schema tại F0-06; thay đổi phá vỡ phải version hóa hoặc pair review. |
| PDF/MinIO khó chạy máy dev | Ưu tiên F0-02/F0-03; dùng Testcontainers và fixture PDF nhỏ cho mọi worker ticket. |
| Cùng sửa `common`/Compose | N1 owner; N2 mở PR nhỏ hoặc pair, không tự đổi primitive bảo mật. |
| Tích hợp muộn | Rebase/merge ít nhất mỗi ngày; bắt buộc demo theo đợt. |
| Scope nở | Việc ngoài bảng phải thêm ticket có owner, dependency và tác động đợt trước khi làm. |

## 6. Nhật ký

| Ngày | Thay đổi | Trạng thái |
|---|---|---|
| 2026-10-27 | Tạo kế hoạch 2 người, phân rã theo ticket nhỏ và vertical slice. | Chưa bắt đầu Đợt 0. |
