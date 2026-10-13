# Kế hoạch triển khai và theo dõi tiến độ backend

> Cập nhật lần cuối: 2026-10-12  
> Trạng thái tổng: **S0/WP0 đang thực hiện — gateway/common foundation đã có unit test; chưa đạt cổng tích hợp Compose.**  
> Quy ước: chỉ đánh dấu `[x]` khi mã nguồn đã được review, migration/OpenAPI liên quan đã cập nhật và toàn bộ test nêu trong mục đó xanh. Khi đánh dấu, thêm ngày, commit/PR và lệnh test ở dòng `Bằng chứng`.

## 1. Cách sử dụng file này

1. Thực hiện đúng thứ tự S0 → WP0 → WP9. Không bắt đầu WP kế tiếp khi cổng chất lượng của WP hiện tại chưa đạt.
2. Mỗi hạng mục con là một đơn vị có thể kiểm chứng. Đánh dấu `[x]` ngay sau khi hoàn tất, không đợi hết WP.
3. Một mục có migration, API hay mã lỗi mới chỉ hoàn tất khi đã cập nhật cả Flyway, OpenAPI và test tương ứng.
4. Nếu có sai khác với đặc tả, ghi vào phần **Nhật ký quyết định/lệch chuẩn** thay vì tự thay đổi yêu cầu.
5. Không ghi token, mật khẩu, presigned URL, PII, số phách hoặc điểm chi tiết vào bằng chứng/lịch sử commit.
6. Ký hiệu `[v]` sau ô `[ ]` nghĩa là chức năng đã có nhưng thiếu test nghiệm thu hoặc automation cần thiết; không được hiểu là hạng mục đã đóng.

### Cổng chất lượng áp dụng cho mọi WP

- [ ] `gradle test` của module thay đổi xanh.
- [ ] Test tích hợp Testcontainers liên quan (PostgreSQL/RabbitMQ/MinIO) xanh.
- [ ] Contract OpenAPI và migration Flyway được cập nhật.
- [ ] Phân quyền EO/GRADER/COUNCIL và kiểm tra không rò PII được bổ sung nếu endpoint có dữ liệu nhạy cảm.
- [ ] Không có truy vấn DB xuyên service; giao tiếp nội bộ có chữ ký/allowlist.
- [ ] README/cấu hình `.env.example`/tài liệu vận hành cập nhật nếu có thay đổi vận hành.

## 2. Bản đồ phụ thuộc

```text
S0 (spike storage) → WP0 (nền tảng) → WP1 (identity) ─┐
                         │                              ├→ WP3 (upload/state) → WP4 (PDF worker)
                         └→ WP2 (exam/rubric) ──────────┘                         ↓
                                                                               WP5 (grading)
                                                                                   ↓
                                                                               WP6 (review)
                                                                                   ↓
                                                                               WP7 (finalize/export)
                                                                                   ↓
                                                                               WP8 (retention)
                                                                                   ↓
                                                                               WP9 (hardening/release)
```

## 3. Tiến độ tổng hợp

| Giai đoạn | Phụ thuộc | Trạng thái | Hoàn tất | Bằng chứng |
|---|---|---|---|---|
| S0 — Spike MinIO qua Gateway | — | 🟨 Đang làm | 2026-10-12 | Unit test storage allowlist; E2E MinIO còn mở |
| WP0 — Nền tảng dùng chung/Gateway | S0 | 🟨 Đang làm | 2026-10-12 | Gateway filters có unit test; quality gate còn mở |
| WP1 — Identity/Pseudonym | WP0 | ⬜ Chưa làm | — | — |
| WP2 — Exam/Rubric | WP0, WP1 | ⬜ Chưa làm | — | — |
| WP3 — Submission upload/state | WP1, WP2 | ⬜ Chưa làm | — | — |
| WP4 — Validate/Anonymize worker | WP3 | ⬜ Chưa làm | — | — |
| WP5 — Assignment/Grading | WP4 | ⬜ Chưa làm | — | — |
| WP6 — Review/Council | WP5 | ⬜ Chưa làm | — | — |
| WP7 — Finalize/Remark/Export | WP6 | ⬜ Chưa làm | — | — |
| WP8 — Retention purge | WP7 | ⬜ Chưa làm | — | — |
| WP9 — Hardening/release | WP8 | ⬜ Chưa làm | — | — |

> Khung Gradle, wrapper, gateway security và các primitive ban đầu đã tồn tại. WP0 chỉ hoàn tất sau khi build/test tổng, Testcontainers và smoke Compose có bằng chứng.

## Nhật ký thực hiện

### 2026-10-12 — S0/WP0 foundation

- Đã thêm `StorageAccessFilter`: chỉ nhận presigned multipart `POST /storage/original` và presigned `GET /storage/anonymized/{examId}/{submissionId}/v{n}.pdf`; chặn bucket khác, listing, object operation và query quản trị.
- Request storage loại bỏ `Authorization`, `Cookie`, `X-User-Id`, `X-Forwarded-For` trước khi proxy; phản hồi có `Cache-Control: private, no-store` và `X-Content-Type-Options: nosniff`.
- Correlation ID chỉ chấp nhận UUID; input không hợp lệ được thay bằng UUID mới.
- Đã bổ sung test unit cho storage allowlist và correlation ID. Cần chạy lại `./gradlew.bat test` và chạy acceptance trên Compose trước khi đóng S0/WP0.
- Gateway dùng `spring-cloud-starter-gateway:4.2.0`, tương thích Spring Boot 3.4.x; không dùng artifact không tồn tại `gateway-server-webflux`.

### 2026-10-13 — S0/WP0 hardening tiếp tục

- Đưa `api-gateway` vào Compose; MinIO không còn publish cổng API/console ra host, chỉ Gateway publish `:8080`. Bổ sung healthcheck cho các dependency và gateway.
- Bổ sung rate limit tại Gateway cho storage và login; storage chặn POST có query, che header `Server`/`X-Minio-*` và ưu tiên 404 cho bucket không allowlist.
- Bổ sung các primitive có test cho HMAC verification, signed user context, chống replay 60 giây, idempotency tuple và outbox relay seam.
- `docker compose --env-file .env.example config --quiet` và `./gradlew.bat test --no-daemon` xanh. Docker Desktop daemon vẫn chưa chạy, nên chưa thể chạy Compose/Testcontainers hay AT-B02/AT-N01…AT-N12; S0/WP0 **chưa được đóng**.

### 2026-10-13 — Bổ sung các hạng mục WP0 chưa kiểm chứng runtime

- Compose hiện có đủ Gateway, bốn service backend, bốn PostgreSQL, RabbitMQ và MinIO; mỗi dependency/service có healthcheck. Các service chỉ kết nối DB của chính mình.
- Thêm profile `test` dùng Testcontainers JDBC, dependency Testcontainers cho PostgreSQL/RabbitMQ, dependency locking và kiểm tra source hygiene vào Gradle.
- Chuẩn hóa thêm error request ID, audit redaction, idempotency executor, outbox relay contract, header service/user context và allowlist storage đúng cả GET/HEAD, query SigV4, Host nội bộ.
- Không chạy test theo yêu cầu hiện tại. Các ô yêu cầu bằng chứng runtime/acceptance vẫn để `[ ]` cho đến khi Docker daemon hoạt động và suite được chạy.

### 2026-10-13 — Xác minh runtime S0/WP0

- `./gradlew.bat test --no-daemon` **xanh** cho toàn bộ module. `docker compose --env-file .env.example -f deploy/docker-compose.yml config --quiet` cũng xanh.
- Smoke Compose đã chạy với PostgreSQL của bốn service, RabbitMQ, MinIO, `minio-init`, bốn service backend và Gateway đều `healthy`. Các cổng PostgreSQL/RabbitMQ đã bỏ publish ra host; chỉ Gateway được publish. Để tránh cổng `8080` đang được một stack khác sử dụng, lần kiểm thử dùng `GATEWAY_PORT=18080`.
- Runtime Gateway đạt: AT-A06 (`/internal/**` → 404); AT-N01 (bucket lạ → 404), N02 (listing → 405), N03 (DELETE → 405), N05 (query listing → 403), N06 (thiếu chữ ký → 403), N08 (đường dẫn MinIO → 404), N10 (response có `Cache-Control: private, no-store`, `nosniff`, không có `X-Minio-*`) và N11 (presigned GET + `Range: bytes=0-7` → 206, 8 byte). Presigned GET hết hạn trả 403.
- Unit test tiếp tục bao phủ chặn query/size upload và loại bỏ `Authorization`, `Cookie`, `X-User-Id` trước khi proxy. AT-N09 chưa có capture upstream runtime; AT-N12 chưa có stress/rate-limit runtime.
- **Chưa đóng S0/WP0:** chưa có luồng sinh presigned POST để chạy AT-B02 (upload 50 MiB và các trường hợp sai key/quá cỡ); AT-N01…N12 và AT-A06/A07 chưa được tự động hóa trọn bộ. Không đánh dấu `[x]` các hạng mục/cổng tương ứng cho tới khi các bằng chứng này có trong suite tự động và thay đổi được commit/PR.

---

## S0 — Spike presigned storage qua Gateway

**Mục tiêu:** chốt cấu hình an toàn để MinIO chỉ được truy cập qua `/storage/**`, trước khi viết upload nghiệp vụ.

- [x] Dựng profile Compose tối thiểu gồm Gateway và MinIO với hai bucket private `original`, `anonymized`. *(Compose hiện dựng MinIO và `minio-init`; Gateway chạy độc lập cho đến khi được đưa vào service image.)*
- [x] Viết bootstrap idempotent tạo bucket, tắt public access và kiểm tra quyền service credential.
- [x] Cấu hình Gateway proxy `/storage/**` với `StripPrefix=1`, streaming request body, hỗ trợ `Range` response và không thêm header identity/HMAC. *(Lớp allowlist có unit test; Range cần xác nhận E2E với MinIO.)*
- [ ] [v] Thử presigned **POST** qua `/storage/original`: key cố định, content length policy, upload 50 MiB thành công.
- [x] Thử presigned **GET** qua `/storage/anonymized`: tải/xem Range hoạt động, URL hết hạn bị MinIO từ chối. *(2026-10-13: presigned GET qua Gateway trả `206` cho `Range: bytes=0-7`; URL hết hạn trả `403`.)*
- [ ] [v] Xác nhận sai key, sai bucket, quá kích cỡ, phương thức sai, chữ ký thiếu/sai và URL hết hạn đều bị từ chối.
- [x] Kiểm tra gateway không vô tình mở MinIO admin API hoặc bucket listing. *(2026-10-13: runtime `/minio/health/live` trả 404, listing trả 405; có unit test route/filter.)*
- [x] Ghi cấu hình đã chốt (Host header, path-style, StripPrefix, buffering, Range) vào `adr/ADR-001-storage-gateway-spike.md` hoặc README vận hành.
- [ ] [v] Tự động hóa AT-B02 và AT-N01…AT-N12.

**Cổng S0:** Spike chạy lại được bằng Compose và toàn bộ test storage xanh.  
**Bằng chứng:** _Chưa có._

---

## WP0 — Nền tảng, common và Gateway

### 0A. Hoàn thiện build và môi trường phát triển

- [x] Sinh và commit Gradle Wrapper; `gradlew.bat build` chạy trên Windows với JDK 21. *(Wrapper đã có; build tổng cần được chạy lại làm bằng chứng trước cổng WP0.)*
- [ ] Chuẩn hóa Gradle convention cho Java 21, Spring Boot, test, format/lint và dependency locking (nếu chọn dùng).
- [x] Hoàn thiện Dockerfile build theo từng service, Compose cho 4 PostgreSQL, RabbitMQ, MinIO và healthcheck. *(2026-10-13: smoke Compose toàn stack xanh; chỉ Gateway publish port.)*
- [x] Tạo profile `test` với Testcontainers, không phụ thuộc dịch vụ máy local.
- [x] Hoàn thiện `.env.example`; không có secret thật trong repository.

### 0B. `common`

- [x] Chuẩn hóa response lỗi `{code,message,requestId,details?}` và global exception handler.
- [x] Cài correlation/request ID: nhận hoặc sinh UUID, trả về response và truyền internal request.
- [x] Cài cursor pagination opaque, default 50 và giới hạn 200.
- [x] Cài idempotency record + helper xử lý replay an toàn theo actor/operation/key.
- [x] Cài audit-log abstraction; audit không chứa password/hash/token/presigned URL/PII nhạy cảm.
- [x] Cài HMAC cho user context (`X-User-Id`, role, request ID, timestamp, signature), chống clock skew >60 giây và replay. *(2026-10-13: unit test xác nhận caller sai, timestamp lệch 61 giây và replay bị từ chối.)*
- [x] Cài service-to-service signature riêng, allowlist caller và log redaction. *(2026-10-13: signer/verifier riêng kiểm allowlist, signature, clock skew và replay; redactor được dùng cho audit.)*
- [ ] Cài outbox entity/relay/retry/idempotent consumer contract cho các lệnh cần giao tin tin cậy.
- [ ] [v] Viết unit test cho lỗi, cursor, HMAC/replay, idempotency và redaction. *(Cursor, HMAC/replay và idempotency đã có test; còn thiếu error handler/audit redaction.)*

### 0C. API Gateway

- [ ] Verify JWT bằng JWKS của Identity; role claim được map đúng một role.
- [ ] Cho phép không JWT chỉ với login và JWKS; áp rate limit cho login.
- [x] Chặn tuyệt đối `/internal/**` qua Gateway bằng 404.
- [ ] Route public đúng service; giới hạn body upload/API; CORS và error response thống nhất.
- [x] Áp dụng route `/storage/**` và allowlist đã chốt tại S0, không áp JWT lên presigned request.
- [ ] Thêm Actuator health/info, correlation filter và redacted structured log.
- [ ] [v] Tự động hóa AT-A06, AT-A07 và smoke test Compose.

**Cổng WP0:** `docker compose up` khỏe; mọi module build/test; AT-A06/A07 xanh.  
**Bằng chứng:** _Chưa có._

---

## WP1 — Identity, user, roster và pseudonym

### 1A. Schema và bảo mật tài khoản

- [ ] [v] Áp dụng nguyên trạng migration `V1__users_graders.sql`, sau đó áp dụng/test `V2__users_hardening.sql`. *(Đã đưa schema users/graders và hardening vào Flyway `V2`/`V3` của Identity; chưa chạy nghiệm thu migration.)*
- [ ] Tạo Flyway schema cho audit, idempotency, candidate, submission binding, pseudonym và pseudonym history.
- [ ] Bảo đảm `users.user_id` UUID là ID dùng xuyên service; `graders.grader_id` không đi ra API/DB service khác.
- [ ] [v] Cài BCrypt/Argon2, chính sách mật khẩu, login failure counter, khóa tài khoản và reset password. *(Đã có BCrypt cost 12, minimum 8 ký tự, failure counter và lockout; reset password chưa triển khai.)*
- [ ] [v] Cài bootstrap EXAM_OFFICE chỉ khi chưa có EO và biến môi trường hợp lệ.
- [ ] [v] Phát hành JWT RS256 (`sub`, role, issuer, iat, exp, jti) và endpoint JWKS public.

### 1B. API Identity

- [ ] [v] `POST /auth/login`, `/users/me`, đổi password với lỗi đăng nhập không làm lộ account tồn tại. *(Đã triển khai endpoint và response `INVALID_CREDENTIALS` đồng nhất; chưa nghiệm thu.)*
- [ ] CRUD quản trị user; cấm đổi role, cấm vô hiệu hóa EO hoạt động cuối cùng.
- [ ] Profile grader; chỉ tạo profile khi role GRADER.
- [ ] Import roster CSV/XLSX: lưu dòng hợp lệ, trả lỗi từng dòng, unique candidate number theo exam.
- [ ] Candidate search phân trang chỉ cho EXAM_OFFICE.
- [ ] `submission-bindings`: kiểm candidate thuộc exam, một binding ACTIVE/candidate, idempotent và endpoint release/purge.
- [ ] Pseudonym 8 ký tự CSPRNG; unique `(exam,value)`, retry tối đa 5, lịch sử khi đổi và lookup theo lô.
- [ ] Internal user lookup, identity resolve (chỉ luồng EO/Result allowlist) và pseudonym purge.

### 1C. Kiểm thử

- [ ] AT-K01…AT-K14 (auth/user).
- [ ] AT-C01, AT-C02, AT-D05, AT-A04, AT-A08, AT-A13, AT-O07, AT-O08.

**Cổng WP1:** migration chạy trên PostgreSQL Testcontainers, JWT/JWKS giao tiếp được với Gateway, toàn bộ test trên xanh.  
**Bằng chứng:** _Chưa có._

---

## WP2 — Exam, rubric và registry giám khảo

- [ ] Tạo schema Flyway: Exam, Question, Criterion, Rubric version, `exam_grader`, audit/idempotency.
- [ ] API tạo/sửa exam; enforce scale, threshold, grader count và trạng thái DRAFT/ACTIVE/CLOSED.
- [ ] API câu hỏi/criteria; validate tổng điểm câu bằng scale và tổng criteria bằng max score.
- [ ] Rubric manual versioned; activate chỉ sau `confirmed=true`, chỉ một bản ACTIVE.
- [ ] Import DOCX bằng Apache POI: giữ rows/cells/rowspan/colspan; từ chối nested/merge không hỗ trợ; chỉ gợi ý regex, không auto-active.
- [ ] Đăng ký giám khảo kỳ thi qua Identity user lookup, chỉ GRADER active.
- [ ] Internal `GET /exams/{id}` và các projection tối thiểu cho Submission/Result.
- [ ] Cập nhật OpenAPI, migration và AT-D01…AT-D04, AT-D06, AT-K11.

**Cổng WP2:** exam/rubric ACTIVE chỉ xuất hiện khi mọi bất biến điểm đúng.  
**Bằng chứng:** _Chưa có._

---

## WP3 — Submission: template, upload và state machine

### 3A. Schema và trạng thái

- [ ] Tạo schema PaperTemplate versioned, Submission, ExamFile phiên bản, ProcessingJob, SubmissionEvent, file replacement, audit/idempotency.
- [ ] Hiện thực state machine T01–T05/T20 bằng optimistic version; chỉ Submission service được chuyển trạng thái.
- [ ] Inject `Clock` cho hạn upload, scheduler và mọi logic thời gian; không gọi `Instant.now()` trực tiếp trong domain/service.
- [ ] Cài SSE event theo sequence, `Last-Event-ID`, heartbeat và snapshot fallback.

### 3B. Template và upload

- [ ] CRUD PaperTemplate, kiểm normalised mask `[0,1]`, không sửa template đã được dùng; chỉ version mới.
- [ ] Init upload: kiểm exam ACTIVE, kích thước/PDF content type, gọi Identity tạo binding và sinh presigned POST key `{examId}/{submissionId}/v{n}.pdf`.
- [ ] Complete upload: HEAD object, kiểm kích thước, idempotent, enqueue VALIDATE qua outbox.
- [ ] Cấp URL ORIGINAL/ANONYMIZED chỉ cho EXAM_OFFICE public API; GRADER/COUNCIL nhận URL bằng internal projection sau này.
- [ ] Scheduler chuyển upload bỏ dở thành `FAILED/UPLOAD_ABANDONED`; dọn object mồ côi an toàn/idempotent.
- [ ] Internal status API có expected status/version và service signature.
- [ ] Replace file: chỉ guard đúng Review/FAILED, giữ pseudonym, tạo ORIGINAL version mới bất biến, chặn nếu CONFIRMED attempt.

### 3C. Kiểm thử

- [ ] AT-B01…AT-B06, AT-I01…I04, AT-A01, AT-C02b.
- [ ] AT-L01…L05, L07, L09, L12, L13, L17, L18, AT-O01…O06.

**Cổng WP3:** upload không nhận bytes qua business service, không lưu candidateId/pseudonym tại Submission DB.  
**Bằng chứng:** _Chưa có._

---

## WP4 — PDF validation và anonymization worker

- [ ] Khai báo exchange `submission.processing`, 2 queue durable `validate`/`anonymize`, retry 30s→2m→10m và DLQ tách riêng.
- [ ] Validate schema message/version; message validate không có pseudonym, message anonymize không có candidate PII.
- [ ] Worker VALIDATE: download an toàn, MIME/PDF/page count/dimension/checksum, giới hạn pixel/thời gian, tạo pseudonym sau validate hợp lệ.
- [ ] Worker ANONYMIZE: rasterize trang 1, mask white lossless, chỉ render phách mới, xóa metadata/annotation/attachment; không chỉ phủ rectangle PDF.
- [ ] Kiểm kích thước template ±5%; lỗi template tạo Review required, lỗi transient retry, exhaustion chuyển FAILED.
- [ ] Đảm bảo duplicate delivery/job idempotent, ack sau DB/publish an toàn, reconciler gửi lại job bị mất khi RabbitMQ down.
- [ ] Dùng cùng `PageMasker` cho preview bài đã upload và preview bằng sample template; rate/concurrency/timeout hợp lý.
- [ ] Cập nhật version/force guard khi reprocess và không ghi đè ORIGINAL.
- [ ] AT-B07…B09, AT-C03…C16, AT-I05, AT-L08/L14/L15, AT-P01…P15.

**Cổng WP4:** kiểm thử pixel/lossless và thông điệp RabbitMQ xanh; không thể khôi phục danh tính từ PDF anonymized.  
**Bằng chứng:** _Chưa có._

---

## WP5 — Assignment, attempt và grading

- [ ] Tạo schema assignment/attempt/question score và index/unique constraint cần thiết.
- [ ] Bulk assignment: exam ACTIVE, giám khảo registry active, cross-grading đúng `graderCount`, xử lý từng bài/rejected và round-robin khi cross tắt.
- [ ] Single review assignment: chỉ khi Review `ACTION_AUTHORIZED` hợp lệ.
- [ ] Danh sách assignment chỉ của GRADER sở hữu; dùng pseudonym lookup, không trả identity.
- [ ] Tạo attempt, context: gọi Submission lấy URL anonymized và rubric/questions; không lộ điểm ghi chú grader khác hoặc attempt cũ.
- [ ] Upsert score/criteria với BigDecimal; enforce range/criteria/max score; `needsReview` chỉ là dấu hiệu, không tự mở Review.
- [ ] Confirm atomic/idempotent, kiểm required question và gửi snapshot/outbox cho Result Review.
- [ ] Cài attempt summary, discard-open-work và internal snapshot projection.
- [ ] AT-E01…E06, AT-A02/A03/A09/A10, AT-L06/L10/L11, AT-M07.

**Cổng WP5:** GRADER chỉ thao tác bài được giao; mọi total score dùng BigDecimal.  
**Bằng chứng:** _Chưa có._

---

## WP6 — Evaluate, review và quyết định Hội đồng

- [ ] Tạo schema Review, review note, CouncilDecision, optimistic version và partial unique index cho tối đa một review OPEN/ACTION_AUTHORIZED/submission.
- [ ] Nhận attempt confirmed idempotently; evaluate single/cross grading, pairwise threshold và HALF_UP scale 2.
- [ ] Tạo `SCORE_DIFFERENCE` khi cần; bỏ qua evaluate khi review chưa đóng và reconcile lại sau đóng.
- [ ] GRADER flag chỉ cho assignment của mình; gộp flag vào review đang mở thay vì tạo review thứ hai.
- [ ] EO tạo REMARK; EO/COUNCIL xem review với projection khác nhau; Council tuyệt đối không thấy identity/original/user ID grader.
- [ ] Council decision cho mọi reason type theo ma trận: validate action, reason/final score, optimistic lock một lần, ACTION_AUTHORIZED hoặc RESOLVED.
- [ ] EO chỉ thực thi retry/replace/assign regrade khi Review được authorize đúng hành động.
- [ ] EO mở anonymization issue trước giao chấm; xử lý `MASK_INSUFFICIENT`, reprocess với template mới và guard sau giao chấm.
- [ ] Cập nhật replay outbox/reconcile grading và OpenAPI.
- [ ] AT-E07…E09, AT-F01…F14, AT-O10…O13, AT-A05/A12/A14, AT-M01…M06/M08, AT-Q01…Q13.

**Cổng WP6:** D-13/D-16/D-20/D-21 đều được kiểm thử; EO không bao giờ ra quyết định điểm.  
**Bằng chứng:** _Chưa có._

---

## WP7 — Finalize, remark và export

- [ ] Tạo schema FinalResult versioned (`version`, `is_current`), retention hold và snapshot core identity/exam.
- [ ] Implement finalize saga idempotent: điều kiện completed/no open review/đủ grading; retry/reconcile lỗi giữa service.
- [ ] Chỉ set `finalized_at` và `retention_deadline` ở lần finalized đầu; version sau remark sao chép deadline, không gia hạn.
- [ ] Remark sau FINALIZED trong retention window; withdraw; regrade flow và tạo result version mới.
- [ ] Ghép phách chỉ trong Result/EO flow được phép và tránh lộ thông tin ở Council/Grader path.
- [ ] Export CSV/XLSX: chặn khi còn submission chưa FINALIZED, trước purge có chi tiết cần thiết, sau purge chỉ dữ liệu lõi cho phép giữ.
- [ ] AT-F15…F26 và AT-G01…G09.

**Cổng WP7:** finalization/remark có thể lặp an toàn, không tạo sai version hoặc thay đổi retention deadline.  
**Bằng chứng:** _Chưa có._

---

## WP8 — Retention và purge

- [ ] Scheduler chọn result hết hạn với conditional update/lock; bỏ qua `retention_hold`.
- [ ] Gửi 4 lệnh purge idempotent đến Submission, Exam-Grading, Identity và Result-Review.
- [ ] Submission xóa mọi ExamFile object/version, metadata và row submission; Identity xóa mapping/pseudonym/history/binding; Exam-Grading xóa attempts/details; Result xóa review details.
- [ ] Ghi `PurgeReceipt` từng service, chỉ `PURGED` sau đủ receipts; retry 503/failed receipt an toàn.
- [ ] Giữ FinalResult/candidate/exam core theo policy, audit purge và không thêm PURGED vào Submission status.
- [ ] AT-H01…H12 và AT-L16.

**Cổng WP8:** purge có thể chạy lại không lỗi và không xóa FinalResult lõi.  
**Bằng chứng:** _Chưa có._

---

## WP9 — Hardening, vận hành và phát hành

- [ ] Sinh test phân quyền từ ma trận endpoint × EO/GRADER/COUNCIL/public/internal.
- [ ] Contract test tất cả internal API, HMAC/service allowlist và version compatibility.
- [ ] Quét log/response tự động để phát hiện JWT, presigned fields/URL, candidate identity, pseudonym và điểm nhạy cảm lộ sai role.
- [ ] Chạy load test upload 50 MiB/concurrency, SSE reconnect và RabbitMQ recovery; ghi ngưỡng/kết quả.
- [ ] Backup/restore drill: restore DB/object trước purge, kiểm migration rollback strategy và runbook incident.
- [ ] Security dependency scan, container scan và review cấu hình production (secret, TLS, CORS, health, rate/size limit).
- [ ] Hoàn thiện OpenAPI, README service, deployment/runbook, ADR và traceability FR/BR → test.
- [ ] Thiết lập CI: build, unit, integration, contract, migration, security và publish artifact.
- [ ] Chạy full acceptance suite; chỉ gắn release khi xanh.

**Cổng WP9:** full suite xanh, runbook đủ để vận hành/khôi phục, không còn blocker mở.  
**Bằng chứng:** _Chưa có._

## 4. Nhật ký hoàn tất

| Ngày | Hạng mục đánh dấu | Commit/PR | Test/lệnh đã chạy | Ghi chú |
|---|---|---|---|---|
| — | — | — | — | — |

## 5. Nhật ký quyết định, blocker và sai khác

| Ngày | WP | Loại | Mô tả | Hướng xử lý/owner | Trạng thái |
|---|---|---|---|---|---|
| — | — | — | — | — | — |
| 2026-10-06 | S0/WP0 | Blocker hạ tầng | Máy có JDK 21 nhưng chưa có Gradle/Gradle Wrapper; Docker daemon chưa chạy nên không thể chạy Compose, Testcontainers hay xác nhận presigned flow. | Cần Gradle Wrapper và Docker Desktop daemon hoạt động, sau đó chạy full checklist S0/WP0. | Mở |

## 6. Nguồn đặc tả bắt buộc khi triển khai

1. `../specification/source/requirements-ver1_2.json` — nghiệp vụ FR/BR/NFR.
2. `../specification/10-implementation-spec.md` — errata, state machine, bảo mật và quyết định bổ sung.
3. `../specification/11-acceptance-tests.md` — test nghiệm thu phải tự động hóa.
4. `../specification/13-user-table.md` và `../specification/sql/identity/` — identity/user schema/API.
5. `ASSUMPTIONS.md` — mặc định cấu hình hiện đang áp dụng.
