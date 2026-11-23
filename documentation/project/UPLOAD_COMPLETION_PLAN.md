# Upload Completion Implementation Plan

> Phiên bản: 1.1 | Ngày: 2026-11-17
> Dựa trên: `specification/10-implementation-spec.md` (v1.5)
> Mục tiêu: Hoàn thiện luồng upload theo spec 10, bao gồm Identity binding, worker VALIDATE/ANONYMIZE, và các endpoint còn thiếu
> v1.1: Đồng bộ spec v1.5 — sửa task con lệch spec (transition T06, retry topology §8), bổ sung replace-file, anonymization-preview/issues, remark/retention, reconciler

## Tổng quan

Luồng nền tảng `init → upload → complete → validate → anonymize` đã được cài đặt
trong mã nguồn. Phần worker và migration vẫn cần một lượt kiểm chứng end-to-end sau
khi triển khai image mới. Các phần còn thiếu chủ yếu là endpoint nội bộ/mở rộng,
scheduler-reconciler, replace-file, preview/retry và finalize/retention.

## Phân chia Tickets

### TICKET 01: Khôi phục candidateId và Implement Identity Binding Flow

**Mức độ ưu tiên:** Cao
**Dependencies:** Không có
**Ước tính:** 4-6 giờ
**Trạng thái:** ✅ HOÀN THÀNH (10/10 task con)
**Luồng liên quan:** Upload (binding khi init/complete) + Identity (ràng buộc 1 thí sinh 1 bài)

#### Task con:
1. ✅ Khôi phục `candidateId` field trong `InitSubmissionRequest.java`
2. ✅ Cập nhật tests để restore candidateId
3. ✅ Implement Identity endpoint `POST /internal/v1/submission-bindings`
   - Request: `{examId, submissionId, candidateId}`
   - Response: 201/200 idempotent
   - Validates: candidate thuộc exam, candidate chưa có binding ACTIVE khác
   - Lưu mapping vào Identity DB (bảng mới: `submission_binding`)
4. ✅ Implement Identity endpoint `POST /internal/v1/submission-bindings/{submissionId}/release`
   - Request: `{reason}` (UPLOAD_ABANDONED, FILE_TOO_LARGE)
   - Response: 200 idempotent
   - Đánh dấu binding = RELEASED
5. ✅ Cập nhật `SubmissionUploadServiceImpl.init()`:
   - Thứ tự: (1) insert Submission UPLOADING, commit
   - (2) gọi Identity submission-bindings
   - (3) nếu lỗi 409 → xóa row vừa tạo và trả lỗi
   - (4) presigned POST, ghi submission_event/SSE
6. ✅ Cập nhật `SubmissionUploadServiceImpl.complete()`:
   - Khi chuyển FAILED (FILE_TOO_LARGE): gọi Identity binding/release
7. ✅ Thêm migration cho Identity DB: bảng `submission_binding` (V5)
8. ✅ Cập nhật OpenAPI cho Identity endpoints
9. ✅ Unit tests cho Identity binding logic
10. ✅ Integration tests cho init flow với binding

**Files đã tạo/sửa:**
- Identity: `SubmissionBindingController.java`, `SubmissionBindingRepository.java`, DTOs (3 files)
- Submission: `IdentityClient.java`, update `SubmissionUploadServiceImpl.java`, update `InitSubmissionRequest.java`, update tests, DTOs (2 files)
- Migration: `V5__submission_binding.sql`
- OpenAPI: `identity.yaml`
- Tests: `SubmissionBindingRepositoryTest.java`, `SubmissionBindingControllerTest.java`, update `SubmissionUploadServiceImplTest.java`

**Acceptance Criteria:**
- ✅ Init request chấp nhận candidateId
- ✅ Identity binding được gọi và lưu mapping
- ✅ Lỗi binding (candidate đã bound) trả 409 và rollback submission
- ✅ Release binding hoạt động idempotent
- ✅ OpenAPI đã cập nhật
- ✅ Tests đã viết

---

### TICKET 02: Implement Worker VALIDATE

**Mức độ ưu tiên:** Cao
**Dependencies:** TICKET 01
**Ước tính:** 8-10 giờ
**Trạng thái:** ✅ HOÀN THÀNH (12/12 task con) — đã xác nhận E2E trên stack thật 2026-11-22
**Luồng liên quan:** Upload (bước VALIDATE) + Review (tạo Review `TEMPLATE_SIZE_MISMATCH`) + Identity (cấp pseudonym)

#### Task con:
1. ✅ Cấu hình RabbitMQ queue `submission.validate.v1`
2. ✅ Implement `ValidationWorker` consumer trong Submission Service
3. ✅ Implement logic validation:
   - Tải object từ MinIO
   - Kiểm tra PDF đọc được (Apache PDFBox)
   - Kiểm tra 1-20 trang
   - Kiểm tra kích thước ±5% template
   - Tính SHA-256 và ghi vào `ExamFile.sha256`
   - Ghi `page_count` vào Submission
4. ✅ Implement retry logic **đúng topology §8**:
   - Queue retry riêng `submission.validate.retry.30s|2m|10m` (TTL 30s/2m/10m, DLX về `submission.processing`)
   - Header `x-attempt` (bắt đầu 0); lỗi transient → publish retry queue tương ứng với attempt+1
   - `attempt > 3` → ProcessingJob=FAILED, Submission=FAILED `VALIDATE_RETRY_EXHAUSTED`
5. ✅ Implement error cases:
   - PDF hỏng → FAILED, failure_code=PDF_CORRUPT (không retry)
   - Số trang sai → FAILED, failure_code=PAGE_COUNT_INVALID (không retry)
   - Kích thước lệch >5% → REVIEW_REQUIRED (T08)
   - Tạo Review ANONYMIZATION với technical_code=TEMPLATE_SIZE_MISMATCH
6. ✅ Implement call Identity `POST /internal/v1/pseudonyms`:
   - Request: `{examId, submissionId, idempotencyKey}`
   - `idempotencyKey = submissionId + ":pseudonym"` (giữ phách khi reprocess, §4.4/§9)
   - Identity tra candidate theo binding (E-14)
7. ✅ Publish job `anonymize` sau khi VALIDATE xong — message đủ schema §8:
   `{schemaVersion:1, jobId, submissionId, examId, templateId, fileVersion, pseudonym, attempt, correlationId}`
   - Message validate **không** chứa pseudonym; `fileVersion` bắt buộc (= current_file_version)
8. ✅ Update submission status **đúng T05/T06**:
   - Worker VALIDATE: `UPLOADED → VALIDATING` khi nhận job; sau validate xong **giữ VALIDATING**, chỉ publish job anonymize
   - `VALIDATING → ANONYMIZING` do Worker ANONYMIZE thực hiện khi nhận job (T06)
9. ✅ Update ProcessingJob với attempt, error_code, error_detail, next_retry_at
10. ✅ Guard message: `schemaVersion != 1` → DLQ; `fileVersion != current_file_version` → ack bỏ qua (§8)
11. ✅ Unit tests cho validation logic
12. ✅ Integration tests với MinIO object thật — `ValidationWorkerMinioTest` 2/2 PASS + E2E stack thật 2026-11-22 (xem header).

**Acceptance Criteria:**
- Worker consume job từ queue validate
- PDF validation đúng theo spec
- SHA-256 được tính và ghi
- Retry qua retry queue đúng delay, tối đa 3 lần
- Identity pseudonym được tạo với idempotencyKey đúng format
- Job anonymize publish message đủ field, submission vẫn VALIDATING chờ ANONYMIZE nhận

---

### TICKET 03: Implement Worker ANONYMIZE

**Mức độ ưu tiên:** Cao
**Dependencies:** TICKET 01, TICKET 02
**Ước tính:** 8-10 giờ
**Trạng thái:** ✅ HOÀN THÀNH — đã xác nhận E2E trên stack thật 2026-11-22 (ANONYMIZE job SUCCEEDED, bản ẩn danh trong bucket `anonymized`)
**Luồng liên quan:** Upload (bước ANONYMIZE) + Review (lỗi template → Review `ANONYMIZATION`) + cơ chế reprocess T17/T18

#### Task con:
1. ✅ Cấu hình RabbitMQ queue `submission.anonymize.v1` (đã có) + retry queues `submission.anonymize.retry.30s|2m|10m` theo §8
2. ✅ Implement `AnonymizationWorker` consumer trong Submission Service
3. ✅ Implement logic anonymization (§7):
   - Tải ORIGINAL file từ MinIO
   - Đọc `mask`/`print` region từ `paper_template.mask` (schema §6.4, tọa độ chuẩn hóa)
   - Trang 1: rasterize RGB 8-bit (200 DPI, tự giảm nếu > `PDF_MAX_RENDER_PIXELS`, sàn 100 DPI), tô mask trắng `#FFFFFF`, vẽ phách đen trong vùng print, `LosslessFactory` (cấm JPEG)
   - Trang 2+: copy nguyên, bỏ annotation/form/attachment; PDF mới không metadata
   - Giới hạn: `PDF_MAX_PAGE_POINTS=14400`, `PDF_MAX_RENDER_PIXELS=40000000`, `PDF_JOB_TIMEOUT_SECONDS=120`
   - Lưu ANONYMIZED vào bucket `anonymized`, key `{examId}/{submissionId}/v{n}.pdf` (cùng version với ORIGINAL nguồn, D-05)
4. ✅ Implement retry logic **đúng topology §8** (giống TICKET 02 task 4, routing key `anonymize`):
   - `attempt > 3` → FAILED `ANONYMIZE_RETRY_EXHAUSTED`
5. ✅ Implement error cases:
   - Lỗi template/region (không retry) → REVIEW_REQUIRED, Review `ANONYMIZATION`/`TEMPLATE_MASK_ERROR`
   - Vượt giới hạn PDF → FAILED `PDF_CORRUPT`/`LIMIT_EXCEEDED` (không retry)
6. ✅ Implement idempotency + overwrite mode (cho T18/T27):
   - Bỏ qua nếu jobId đã `SUCCEEDED`; check `fileVersion` vs `current_file_version`
   - Skip khi đã `READY_FOR_GRADING` và không `overwrite`
   - `overwrite=true` và `templateId` (reprocess) **đọc từ ProcessingJob theo jobId, không tin message** (§8) → cần cột lưu trong `processing_job` hoặc bảng job payload
   - Ghi đè object ANONYMIZED cùng key (atomic ở S3), update ExamFile ANONYMIZED (size_bytes, sha256) trong cùng transaction T09
7. ✅ Ghi `ExamFile ANONYMIZED` (is_current=true, version = ORIGINAL.version)
8. ✅ Update submission status **đúng T06/T09**:
   - Khi nhận job: `VALIDATING → ANONYMIZING` (T06)
   - Sau khi ghi bản ẩn danh: `ANONYMIZING → READY_FOR_GRADING` (T09)
9. ✅ Guard message: `schemaVersion != 1` → DLQ; `fileVersion` stale → ack bỏ qua
10. ✅ Unit tests cho anonymization logic + test pixel vùng mask (§7.4)
11. ✅ Integration tests với PDF mẫu + MinIO thật — `AnonymizationWorkerMinioTest` PASS với `mau-giay-thi-i.pdf` + E2E stack thật 2026-11-22 (xem header).

**Acceptance Criteria:**
- Worker consume job từ queue anonymize
- Vùng phách được phủ đúng theo template (§7.4 pixel test pass)
- Số phách từ message/Identity được ghi
- ANONYMIZED file lưu đúng key/bucket, ORIGINAL không đổi
- Retry qua retry queue đúng delay
- Lỗi template tạo Review đúng, không retry

---

### TICKET 04: Implement Identity Internal Endpoints (Batch Lookup + Purge)

**Mức độ ưu tiên:** Trung bình
**Dependencies:** TICKET 01
**Ước tính:** 4-6 giờ
**Trạng thái:** 🟡 ĐANG THỰC HIỆN (task 1-3 xong, đã verify live 2026-11-23)
**Luồng liên quan:** Hạ tầng Identity — phục vụ Chấm (pseudonyms lookup cho grader context) + Retention (purge endpoint §9.4)

#### Task con:
1. ✅ Implement `POST /internal/v1/pseudonyms/lookup`
   - Request: `{submissionIds:[uuid]}`
   - Response: `{items:[{submissionId,pseudonym}]}`
   - Không trả candidate (theo spec)
2. ✅ Implement `POST /internal/v1/users/lookup`
   - Request: `{userIds:[uuid]}`
   - Response: `{items:[{userId,username,fullName,role,isActive}]}`
   - Allowlist cho Exam&Grading, Result — `UserLookupController` riêng với `ALLOWED_CALLERS = {exam-grading-service, result-review-service}` (submission-service bị 403)
   - Không trả password_hash
3. ✅ Implement `DELETE /internal/v1/identity/submissions/{id}/pseudonym` (§9 purge, xóa cả `submission_binding`)
   - Response `{deletedPseudonyms:int,status:"DONE"}`, idempotent (replay → 200, count=0)
   - Caller allowlist chỉ `result-review-service` (service orchestration purge §9.4)
   - `@Transactional` xóa pseudonym + binding trong 1 transaction
   - Verify live: purge submission E2E → xóa đúng 2 rows trong identity_db; replay → DONE/0; submission-service → 403
   - Lưu ý: chưa có bảng `pseudonym_history` (spec nhắc PseudonymHistory cho chỉnh phách thủ công — thuộc phạm vi khác, chưa implement)
4. ✅ Cập nhật OpenAPI — `identity.yaml` đã có `/pseudonyms`, `/pseudonyms/lookup`, `/users/lookup`, `DELETE /identity/submissions/{id}/pseudonym` (2026-11-23)
5. ✅ Unit tests — `PseudonymControllerTest` (7, gồm 3 purge tests) + `UserLookupControllerTest` (4) PASS
6. 🟡 Integration tests — đã verify live trên stack Docker (cả 3 endpoint, positive + negative); chưa có automated integration test (Testcontainers)

**Acceptance Criteria:**
- Pseudonyms lookup trả đúng số phách theo submissionIds
- Users lookup trả thông tin user theo allowlist
- Không lộ password_hash
- Purge xóa pseudonym + binding đúng retention flow

---

### TICKET 05: Implement Submission Internal Endpoints

**Mức độ ưu tiên:** Trung bình
**Dependencies:** TICKET 01, TICKET 02, TICKET 03
**Ước tính:** 6-8 giờ
**Trạng thái:** 🟡 THỰC HIỆN MỘT PHẦN (mới có endpoint chuyển trạng thái có ký HMAC)
**Luồng liên quan:** Internal API đa luồng — Chấm (attempt-summary/discard-open-work, thuộc exam-grading-service) + Kết quả/Remark (finalize T20) + Review/Chấm (file access ANONYMIZED)

#### Task con:
1. ✅ Implement `GET /internal/v1/submissions/{id}`
   - Response: `{submissionId,examId,status,version:int}`
2. ✅ Implement `POST /internal/v1/submissions/{id}/files/{type}/access`
   - type=ORIGINAL|ANONYMIZED
   - Response: `{url,expiresAt}` (5 phút)
   - Service credential, caller đã tự kiểm quyền
   - ANONYMIZED của bản đang bị thay (VALIDATING/ANONYMIZING reprocess) → 409 `FILE_NOT_READY` (§4.6)
3. ✅ Implement `POST /internal/v1/submissions/{id}/finalize`
   - Request: `{finalizedAt,retentionDeadline,idempotencyKey}`
   - Response: `{status:"FINALIZED",version:int}`
   - Idempotent theo submissionId; retention_deadline = finalized_at + 1 năm (T20)
4. ✅ Implement `POST /internal/v1/submissions/{id}/status`
   - Request: `{expectedStatus?,newStatus,reasonCode?,progress?,finalizedAt?,idempotencyKey?}`
   - Response: `{submissionId,status,version:int}`
   - Optimistic version
5. ✅ Implement `GET /internal/v1/grading/submissions/{id}/attempt-summary`
   - Response: `{confirmedCount,draftCount,openAssignmentCount}`
6. ✅ Implement `POST /internal/v1/grading/submissions/{id}/discard-open-work`
   - Idempotent theo replacementId
   - Hủy assignment mở, đặt lượt DRAFT → DISCARDED (dùng cho replace-file §4.4)
7. ✅ Cập nhật OpenAPI — thêm `submission.yaml` (4 internal endpoints) + `exam-grading.yaml` (attempt-summary, discard-open-work)
8. ✅ Unit tests — `InternalSubmissionControllerTest` (13) + `InternalGradingControllerTest` (5) PASS
9. ✅ Integration tests — `InternalSubmissionControllerIT` (6 tests, submission_it @5433) + `InternalGradingControllerIT` (4 tests, exam_grading_it @5434) PASS trên Postgres compose; Flyway rebuild schema mỗi run, IT dùng Postgres compose + database riêng (submission_it, exam_grading_it)

**Acceptance Criteria:**
- Tất cả internal endpoints hoạt động đúng theo spec
- Optimistic versioning hoạt động
- Idempotency keys hoạt động

---

### TICKET 06: Implement Submission Public Endpoints (Cơ bản)

**Mức độ ưu tiên:** Trung bình
**Dependencies:** TICKET 05
**Ước tính:** 6-8 giờ
**Trạng thái:** ✅ HOÀN THÀNH (toàn bộ public endpoints + OpenAPI đúng contract spec, chỉ EXAM_OFFICE; unit + integration tests PASS)
**Luồng liên quan:** Upload (public API trạng thái/file/SSE) + Remark/Retention (`PUT /pseudonym` chặn sau FINALIZED) + Review (`GET identity` ghép phách)

#### Task con:
1. ✅ Implement `GET /api/v1/submissions/{submissionId}`
   - Response: `{submissionId,examId,status,pageCount?,progress,int,error?,updatedAt}`
   - Chỉ EXAM_OFFICE (theo E-06)
2. ✅ Implement `GET /api/v1/exams/{examId}/submissions` 
   - Query: `status?,cursor?,limit?` (limit mặc định 50, max 200 — D-06)
   - Response: `{items:[...],nextCursor}` — keyset pagination trên `(updated_at, submission_id)` DESC, cursor opaque base64url
   - Item dùng chung projection spec với detail; chỉ EXAM_OFFICE (gateway rule `/api/v1/exams/*/submissions` GET → EO, trước rule exams/** cho GRADER/COUNCIL)
3. ✅ Implement `GET /api/v1/submissions/{id}/identity` 
   - Response: `{candidateNumber,fullName,className,pseudonym}` (không trả candidateId)
   - Chỉ EXAM_OFFICE (gateway `GET /api/v1/submissions/**` → EO), ghi audit `SUBMISSION_IDENTITY_VIEWED` vào audit_log
   - Submission Service gọi Identity internal `GET /internal/v1/submissions/{id}/identity` (HMAC, join binding→candidate→pseudonym, 404 BINDING_NOT_FOUND)
4. ✅ Implement `PUT /api/v1/submissions/{id}/pseudonym` 
   - Body `{pseudonym,reason}`, reason bắt buộc (422 `REASON_REQUIRED`); chỉ khi chưa FINALIZED (409 `INVALID_STATE`)
   - Submission Service → Identity `PUT /internal/v1/submissions/{id}/pseudonym` (HMAC): update value + insert `pseudonym_history` trong 1 transaction; trùng value → idempotent; trùng phách exam → 409 `PSEUDONYM_TAKEN`; chưa có pseudonym → 404→409
   - `changed_by` = JWT `sub` (users.user_id, D-14); audit `SUBMISSION_PSEUDONYM_CHANGED` ở submission DB
   - Migration V7: bảng `pseudonym_history` + nới `pseudonym.value` varchar(8)→varchar(64) theo spec
5. ✅ Implement `GET /api/v1/submissions/{id}/files` 
   - Response `{items:[{type,version,isCurrent,sizeBytes,sha256?,createdAt}]}` — không trả object key/URL (§4.4)
   - `ExamFileRepository.findAllBySubmission` + record `ExamFileVersion`; sha256/sizeBytes optional
6. ✅ Implement SSE `GET /api/v1/exams/{examId}/submissions/events` 
   - Event: `submission-status`, data `{submissionId,status,progress,updatedAt}`, `id:<event_id>`
   - Poll `submission_event` (đã có sẵn exam_id/event_id) mỗi 1s; heartbeat comment 15s; `Last-Event-ID` replay; timeout 30m (`app.sse.timeout-minutes`); chỉ EXAM_OFFICE (gateway `/api/v1/exams/*/submissions/**`)
7. ✅ Cập nhật OpenAPI — `gateway.yaml` đủ 6 public endpoints của ticket (detail, list+phân trang, identity, files, pseudonym PUT, SSE events); `identity.yaml` thêm `GET /internal/v1/submissions/{id}/identity` + `PUT /internal/v1/submissions/{id}/pseudonym`. Cả 2 file YAML valid.
8. ✅ Unit tests — `SubmissionControllerTest` (MockMvc standalone: detail/list/identity/files/pseudonym, shape + không leak objectKey/failureCode, param lỗi → 400) + `GatewaySecurityConfigTest` (WebTestClient + ReactiveJwtDecoder: EO allow, GRADER/COUNCIL 403 trên detail/list/identity/files/pseudonym/SSE, exams reads vẫn cho GRADER/COUNCIL). Cộng dồn unit test theo từng task: `SubmissionUploadServiceImplTest` 28, `PseudonymControllerTest` 15, `SubmissionEventsControllerTest` 3 — tất cả PASS
9. ✅ Integration tests — `SubmissionPublicApiIT` (8 tests, Postgres `submission_it` thật qua compose, Flyway migrate; Identity/RabbitMQ mock, storage in-memory): init→upload→complete→detail/list/files/events end-to-end, keyset pagination 3 trang không trùng, param lỗi → 400, identity shape không leak candidateId + audit không PII, pseudonym update forward JWT sub + audit, FINALIZED → 409, SSE replay từ Last-Event-ID

**Acceptance Criteria:**
- GET submissions hoạt động đúng với phân trang
- SSE events được phát đúng
- Identity lookup chỉ cho EXAM_OFFICE
- PUT pseudonym ghi history đúng

---

### TICKET 07: Implement Job Scheduler cho Cleanup + Reconciler

**Mức độ ưu tiên:** Trung bình
**Dependencies:** TICKET 01
**Ước tính:** 4-6 giờ
**Trạng thái:** ✅ HOÀN THÀNH
**Luồng liên quan:** Vận hành — Upload (dọn upload hết hạn) + pipeline (reconciler ProcessingJob của T02/T03) + Retention (purge §9.5, liên quan remark window của T11)

#### Task con:
1. ✅ Implement scheduler chuyển UPLOADING → FAILED sau upload_expires_at
   - Cron: mỗi 5 phút (`@Scheduled` `app.jobs.upload-cleanup-cron`, mặc định `0 */5 * * * *`)
   - Query: `findExpiredUploading(now)` — `WHERE status='UPLOADING' AND upload_expires_at < now`
   - Action: `failUploading` atomic UPDATE → FAILED + failure_code=UPLOAD_ABANDONED + version+1 (0 rows khi race với complete); append `submission_event` FAILED + audit `SUBMISSION_STATUS_CHANGED`
   - Gọi Identity `releaseBinding(submissionId,"UPLOAD_ABANDONED")` (best-effort, idempotent); `@EnableScheduling` đã bật; mỗi row xử lý độc lập, lỗi 1 row không chặn batch
2. ✅ Implement scheduler dọn orphan object trong MinIO
   - Cron: mỗi giờ (`@Scheduled` `app.jobs.orphan-cleanup-cron`, mặc định `0 0 * * * *`)
   - `ObjectStorageGateway.list(bucket)` mới (S3: MinIO `listObjects` recursive; InMemory: track lastModified + Clock); `ObjectInfo(objectKey,lastModified,sizeBytes)`
   - Guard 3 lớp: `lastModified` null/quá mới (< `app.jobs.orphan-grace-minutes`=60) giữ lại; có `exam_file.object_key` giữ lại; `submission.object_key` còn UPLOADING giữ lại (upload đang chạy/chưa complete chưa có exam_file)
   - Xóa phần còn lại (abandoned FAILED, FILE_TOO_LARGE, foreign); per-object try/catch, list lỗi → abort êm
3. ✅ Implement **reconciler** (§8): mỗi 5 phút requeue `ProcessingJob` ở `QUEUED` quá 10 phút (cả step VALIDATE và ANONYMIZE) — chống mất message
   - `ProcessingJobReconciler` (`app.jobs.reconcile-cron` default `0 */5 * * * *`, stale `app.jobs.reconcile-stale-minutes`=10, sweep 200/lần)
   - `findStaleQueued`: `status='QUEUED' AND updated_at < cutoff AND (next_retry_at IS NULL OR <= now)` join submission lấy exam_id
   - Rebuild message từ job row (không tin payload cũ): VALIDATE → `ValidateJobMessage` (templateId/force/fileVersion/attempt từ job, fallback template submission); ANONYMIZE → re-fetch pseudonym qua `createPseudonym` cùng deterministic key `submissionId:pseudonym`
   - Skip submission FAILED/FINALIZED/missing; worker idempotent theo jobId nên duplicate delivery an toàn; per-job try/catch
4. ✅ Implement scheduler retention/purge (§9.3/§9.5, FR-025, D-03) — orchestrator ở result-review-service theo spec
   - `RetentionScheduler` daily cron `app.retention.cron`/`RETENTION_CRON` default `0 0 2 * * *`; `JdbcFinalResultRepository`: chọn `is_current AND !retention_hold AND retention_deadline<=now AND purge_status∈{PENDING,FAILED}` → claim conditional → IN_PROGRESS (thua race với remark)
   - `PurgeClient`: 3 lệnh purge HMAC caller `result-review-service` + `Idempotency-Key=resultId:retention:v1` (404 = DONE)
   - Purge endpoints mới: submission `DELETE /internal/v1/submissions/{id}/detail` (xóa object mọi version cả 2 bucket + exam_file/event/processing_job/audit_log + submission row, chỉ result-review-service); exam-grading `DELETE /internal/v1/grading/submissions/{id}/detail` (xóa question_score/attempt/assignment); identity purge đã có sẵn
   - Self-delete review/council_decision/review_note/audit_log; đủ 4 receipts → `purge_status=PURGED` + `submission_id=NULL` (migration V3 nới NOT NULL); lỗi → FAILED retry lần sau
   - Lưu ý: scheduler chỉ có dữ liệu khi Ticket 11 (finalize saga) ghi `final_result`
5. ✅ Cấu hình Spring Scheduler — `@EnableScheduling` trên cả submission-service và result-review-service; `SchedulingConfig` ThreadPoolTaskScheduler pool `app.jobs.pool-size`=4 (thread `submission-job-*`, graceful shutdown 30s); `application.yml` khai báo đủ cron props map env spec §10: `ABANDONED_SCAN_CRON`, `ORPHAN_SCAN_CRON`, `ORPHAN_GRACE_MINUTES`, `RECONCILE_CRON`, `RECONCILE_STALE_MINUTES`, `RETENTION_CRON` (result-review)
6. ✅ Unit tests cho scheduler logic — `UploadCleanupJobTest` (5), `OrphanObjectCleanupJobTest` (7), `ProcessingJobReconcilerTest` (7), `RetentionSchedulerTest` (6), `PurgeClientTest` (4: URL/HMAC/Idempotency-Key/404=DONE), purge endpoint tests trong `InternalSubmissionControllerTest` (+3) và `InternalGradingControllerTest` (+2) — tất cả PASS
7. ✅ Integration tests với PostgreSQL + MinIO — `SchedulerJobsIT` (submission_it @5433, 6 tests: upload-abandonment FAILED+event+audit+releaseBinding, orphan cleanup InMemory aging + MinIO thật `original` bucket với grace âm, reconciler requeue VALIDATE/ANONYMIZE + skip fresh/terminal, purge endpoint xóa hết + idempotent + 403 sai caller); `InternalGradingControllerIT` +1 purge test (exam_grading_it @5434); `RetentionSchedulerIT` (result_review_it @5435, 5 tests: PURGED+submission_id NULL+đúng thứ tự 3 receipts+self-delete, FAILED retry, skip held/future/IN_PROGRESS, lỗi→FAILED tiếp tục candidate sau, null submissionId đóng êm); `result-review-service/build.gradle` thêm `user.timezone=UTC` cho test JVM (pgjdbc gửi TimeZone startup param, server không nhận `Asia/Saigon`)

**Acceptance Criteria:**
- Upload expired chuyển FAILED đúng thời gian
- Binding được release khi upload failed
- Orphan objects được dọn sạch
- Job QUEUED quá hạn được requeue
- Retention purge chạy đúng hạn

---

### TICKET 08: Update Migration cho Identity Binding

**Mức độ ưu tiên:** Cao (thuộc TICKET 01)
**Dependencies:** Không có
**Ước tính:** 2-3 giờ
**Trạng thái:** ✅ HOÀN THÀNH (được thực hiện cùng TICKET 01)
**Luồng liên quan:** Schema Identity binding — nền cho TICKET 01

#### Task con:
1. Tạo migration cho Identity DB:
   - Bảng `submission_binding`:
     - `binding_id uuid PK`
     - `exam_id uuid`
     - `submission_id uuid UNIQUE`
     - `candidate_id uuid FK Candidate`
     - `status enum ACTIVE|RELEASED`
     - `created_at timestamptz`
     - `released_at timestamptz nullable`
   - Index: `(exam_id, candidate_id, status)` WHERE status='ACTIVE'
2. Cập nhật OpenAPI schema
3. Test migration

**Acceptance Criteria:**
- Migration chạy thành công
- Schema đúng theo spec
- Index hỗ trợ query hiệu quả

---

### TICKET 09: Implement Replace-File Flow (D-19, T24, §4.4)

**Mức độ ưu tiên:** Cao
**Dependencies:** TICKET 02, TICKET 03, TICKET 05
**Ước tính:** 8-10 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN
**Luồng liên quan:** Upload (file version mới, validate/anonymize lại) + Chấm (hủy assignment/attempt DRAFT qua discard-open-work) + Review (đóng Review `REPLACE_FILE`) + Identity (giữ phách)

#### Task con:
1. Implement `POST /api/v1/submissions/{id}/replace-file` (EO)
   - Body `{reason, fileName, fileSizeBytes, contentType:"application/pdf"}`, reason bắt buộc
   - Kiểm `fileSizeBytes ≤ 52428800`, version = `current_file_version + 1`, tạo `file_replacement` row + presigned POST
2. Implement `POST …/replace-file/{replacementId}/complete`
   - HEAD object; kiểm điều kiện T24 (Review ACTION_AUTHORIZED + REPLACE_FILE, không lượt CONFIRMED)
   - Theo thứ tự: (1) `discard-open-work` idempotent (2) một transaction: ghi ExamFile ORIGINAL mới, đổi status → UPLOADED, audit REPLACE_FILE_COMPLETED
   - Publish job validate với `fileVersion` mới
3. Implement `DELETE …/replace-file` (hủy replacement đang chờ)
4. Migration bảng `file_replacement` (§4.4: replacement_id, submission_id, version, object_key, declared_size_bytes, status, expires_at…)
5. Xử lý lại từ VALIDATE (T05…): đọc trang/kích thước ±5%, SHA-256 `v n`, **giữ nguyên phách** (Identity `POST /pseudonyms` cùng `idempotencyKey=submissionId:pseudonym` trả phách cũ)
6. Bản ORIGINAL cũ giữ nguyên đến hết retention, chỉ EO xem qua `?version=`; GRADER chỉ nhận ANONYMIZED hiện hành
7. Nếu reprocess vào REVIEW_REQUIRED/FAILED mới → EO được replace-file lần nữa (§4.4)
8. Audit `REPLACE_FILE_REQUESTED/COMPLETED` (reason, version, actor_id — không object key/URL)
9. Unit + integration tests

**Acceptance Criteria:**
- Replace-file chỉ chạy khi đủ điều kiện T24
- Phách giữ nguyên sau thay file
- Saga hủy assignment/lượt DRAFT qua discard-open-work
- Audit đầy đủ, không lộ object key

---

### TICKET 10: Implement Anonymization Preview + Issues (D-23/D-24, §4.5/§4.6, T27/T28)

**Mức độ ưu tiên:** Trung bình
**Dependencies:** TICKET 03, TICKET 06
**Ước tính:** 8-10 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN
**Luồng liên quan:** Review (issues T27/T28, retry T17–T19) + Upload pipeline (worker overwrite ANONYMIZED) + dùng chung `PageMasker` với T03 + FILE_NOT_READY của T05 task 2

#### Task con:
1. Implement `POST /api/v1/submissions/{id}/anonymization-preview` (chỉ EO)
   - Body `{version?, mode?:MASKED|OUTLINE, dpi?:50-200|ANON_RENDER_DPI, override?:{mask,print?}}`
   - `override` thử tọa độ không lưu, kiểm theo §6.4 (ngoài [0,1] → 422 `INVALID_REGION`)
   - Dùng **chung `PageMasker`** với worker (§7) — preview và kết quả thật không lệch
   - Không lưu gì; chỉ audit `ANONYMIZATION_PREVIEWED`; giới hạn `PREVIEW_MAX_CONCURRENCY`, `PREVIEW_TIMEOUT_SECONDS`, rate limit 10 lần/phút/người
2. Implement `POST /api/v1/submissions/{id}/anonymization-issues` (T27)
   - Body `{reason}` bắt buộc; chỉ khi `READY_FOR_GRADING`, có ExamFile ANONYMIZED hiện hành, không Review chưa đóng
   - Thứ tự: tạo Review `ANONYMIZATION`/`MASK_INSUFFICIENT` OPEN → transition T27 → `REVIEW_REQUIRED` (201 `{reviewId,status:"OPEN"}`)
3. Implement `POST /api/v1/submissions/{id}/retry` mở rộng (T17/T18/T19)
   - Body `{step, force?, reviewId?, templateId?}`
   - `REVIEW_REQUIRED`: bắt buộc `reviewId` đang `ACTION_AUTHORIZED` với action tương ứng (`FORCE_ANONYMIZE`→T17, `REPROCESS`→T18)
   - T18 reprocess: tạo ProcessingJob ANONYMIZE với `overwrite=true` + `templateId?` (đọc từ job, không tin message); template mới kiểm lại ±5% mọi trang → lệch thì T11, `template_id` giữ nguyên
   - `FAILED`: T19 theo `failure_code` + step khớp
4. Worker ANONYMIZE xử lý overwrite: ghi đè object cùng key `anonymized/{examId}/{submissionId}/v{n}.pdf`, update ExamFile, về `READY_FOR_GRADING` (T09); hết retry → `FAILED/ANONYMIZE_RETRY_EXHAUSTED`
5. DISMISS Review `MASK_INSUFFICIENT` → T28 về `READY_FOR_GRADING` (phía Result/Review service)
6. Unit + integration tests

**Acceptance Criteria:**
- Preview MASKED/OUTLINE đúng pixel, override không lưu
- Issues mở Review đúng T27, chặn sai trạng thái
- Retry T17/T18/T19 ràng buộc Review ACTION_AUTHORIZED
- Reprocess ghi đè atomic, giữ ORIGINAL

---

### TICKET 11: Implement Remark + Remark Window + Finalize Flow (§4.x, T20-T23, T16)

**Mức độ ưu tiên:** Trung bình
**Dependencies:** TICKET 05, TICKET 06
**Ước tính:** 6-8 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN
**Luồng liên quan:** Remark/Phúc khảo (T21–T23, remark window §9.5) + Kết quả (finalize saga T20, FinalResult versioning) + Retention (`retention_hold`, `retention_deadline` do T07 purge dùng)

#### Task con:
1. Implement `POST /api/v1/submissions/{id}/remark` (EO)
   - Body `{reason}` bắt buộc; submission `COMPLETED`/`FINALIZED` trong cửa sổ §9.5
   - Ngoài cửa sổ → 409 `REMARK_WINDOW_CLOSED`; đã purge → 409 `PURGED_DATA_UNAVAILABLE`
   - Tạo Review `REMARK` OPEN, lưu `previous_status`, T21
2. Implement xử lý kết thúc remark (T23): EO `withdraw` / COUNCIL `DISMISS` → trả `previous_status`
3. Finalize saga T20: `COMPLETED → FINALIZED` — set `finalized_at=now`, `retention_deadline=finalized_at+1 năm` (cộng theo lịch, UTC), FinalResult snapshot; idempotent
4. `PUT /pseudonym` chỉ khi chưa FINALIZED; sau FINALIZED phách bất biến
5. Cửa sổ remark §9.5: `FINALIZED` cần `purge_status=PENDING`, `now < retention_deadline`, `REMARK_WINDOW_DAYS` check trên `first_finalized_at`
6. Unit + integration tests

**Acceptance Criteria:**
- Remark chỉ trong cửa sổ, đúng lỗi 409
- Finalize set retention đúng 1 năm lịch
- T20/T21/T23 transition đúng

---

### TICKET 12: Đồng bộ ProcessingJob schema + ExamFile schema theo spec

**Mức độ ưu tiên:** Cao (làm cùng TICKET 02/03)
**Dependencies:** Không có
**Ước tính:** 2-3 giờ
**Trạng thái:** 🟡 CÀI ĐẶT XONG, CẦN KIỂM CHỨNG MIGRATION TRÊN DB TRIỂN KHAI
**Luồng liên quan:** Schema nền upload pipeline — điều kiện của TICKET 02/03

#### Task con:
1. ✅ Migration `processing_job`: đảm bảo cột `attempt`, `error_code`, `error_detail`, `next_retry_at`, status enum gồm `QUEUED|PROCESSING|SUCCEEDED|FAILED`
2. ✅ Migration `exam_file` theo §5: `version int NOT NULL DEFAULT 1`, `UNIQUE(submission_id,type,version)` + partial unique `(submission_id,type) WHERE is_current`
3. ✅ Migration `processing_job` thêm chỗ lưu `overwrite`/`templateId` cho job reprocess (T18/T27) — worker đọc từ job, không tin message
4. ✅ Kiểm tra `submission.current_file_version`, `page_count`, `failure_code` columns khớp data model
5. ⚠️ Test migration trên DB thật sau khi deploy image/migration mới

**Acceptance Criteria:**
- Schema khớp §5/§8
- Worker đọc overwrite/templateId từ job
- Migration không phá data hiện có

---

## Thứ tự thực hiện khuyến nghị

```
TICKET 08 (Migration) → TICKET 12 (Schema sync) → TICKET 01 (Identity Binding)
→ TICKET 02 (Worker VALIDATE) → TICKET 03 (Worker ANONYMIZE)
→ TICKET 04 (Identity Batch Lookup + Purge)
→ TICKET 05 (Submission Internal) → TICKET 06 (Submission Public)
→ TICKET 09 (Replace-File) → TICKET 10 (Preview + Issues + Retry)
→ TICKET 11 (Remark/Finalize) → TICKET 07 (Scheduler + Reconciler)
```

## Tổng thời gian ước tính

- TICKET 01: 4-6 giờ
- TICKET 02: 8-10 giờ
- TICKET 03: 8-10 giờ
- TICKET 04: 4-6 giờ
- TICKET 05: 6-8 giờ
- TICKET 06: 6-8 giờ
- TICKET 07: 6-8 giờ
- TICKET 08: 2-3 giờ
- TICKET 09: 8-10 giờ
- TICKET 10: 8-10 giờ
- TICKET 11: 6-8 giờ
- TICKET 12: 2-3 giờ

**Tổng cộng:** ~68-88 giờ (khoảng 9-11 ngày làm việc)

## Dependencies giữa tickets

```
TICKET 08 ──→ TICKET 01 ──→ TICKET 02 ──→ TICKET 03 ──→ TICKET 09 ──→ TICKET 10
TICKET 12 ──→ TICKET 02/03 (schema processing_job/exam_file)
                                      ├─→ TICKET 04
                                      └─→ TICKET 05 ──→ TICKET 06 ──→ TICKET 11
TICKET 01/05 ──→ TICKET 07
```

## Notes

- Mọi endpoint mới phải cập nhật OpenAPI tương ứng
- Mọi logic thời gian dùng `java.time.Clock` inject (theo §0)
- Không service nào truy vấn DB của service khác
- Không đưa PII/phách/điểm câu vào log; object key không chứa PII/phách
- Test cả idempotency key flow
- Test cả error cases và retry logic
- Message RabbitMQ phải đủ schema §8: `{schemaVersion:1,jobId,submissionId,examId,templateId,fileVersion,force?,pseudonym?,attempt,correlationId}`
- `overwrite`/`templateId` reprocess đọc từ `ProcessingJob`, không tin message (§8)
