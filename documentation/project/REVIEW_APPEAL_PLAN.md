# Review & Phúc khảo (Remark) Implementation Plan

> Phiên bản: 1.0 | Ngày: 2026-11-23
> Dựa trên: `specification/10-implementation-spec.md` (v1.5) + `source/05-api-contracts-ver-final.md` (v1.3)
> Mục tiêu: Hoàn thiện luồng Review (GRADER_FLAG, SCORE_DIFFERENCE) và Phúc khảo (REMARK), gồm quyết định Hội đồng, chấm lại (REGRADE), finalize có versioning và retention_hold.
> v1.0: Bổ sung các phần còn thiếu so với code hiện tại; luồng ANONYMIZATION (T08/T11/T17/T18/T24/T27/T28) đã xong ở UPLOAD_COMPLETION_PLAN TICKET 09/10, plan này chỉ nhắc lại điểm nối.

## Trả lời nhanh: luồng này thuộc service nào?

**Không thuộc riêng result-review-service.** Review là aggregate của result-review, nhưng luồng chạy qua 3 service:

| Thành phần | Owner | Ghi chú |
|---|---|---|
| `review`, `review_note`, `council_decision`, `final_result` (+`retention_hold`, versioning) | result-review-service | Đã có bảng V2–V4 |
| `POST /reviews/{id}/decision`, `/remark`, `/withdraw`, `/finalize`, `POST /submissions/{id}/reviews` | result-review-service | Result là orchestrator (§12): ghi outbox → gọi Submission `/status` |
| `submission.status` transitions T13–T16, T20–T23 | submission-service | Qua `POST /internal/v1/submissions/{id}/status` — **đã có đủ transition** trong `SubmissionStatusMachine` |
| Lượt chấm, `question_score`, `is_regrade`, SINGLE_REVIEW assignment, `grading-snapshot`, `attempts-confirmed`, `grader-flag` | exam-grading-service | **Chưa implement** — prerequisite của plan này |

## Hiện trạng code (đã kiểm chứng)

**Đã có:**
- result-review: `review`/`review_note`/`council_decision`/`final_result` (V2–V4: `authorized_action`, `resolution_type`, `created_by`, unique index 1 review chưa đóng); `ReviewController` (list/detail/decision/council-decision/results/export); `InternalReviewController` (`POST /reviews` chỉ ANONYMIZATION, `replace-authorization`, `{id}/state`, `resolve-action`, `resolve-replace`); `RetentionScheduler` + `PurgeClient` (đã tôn trọng `retention_hold`, `resumeGrading` cho T28).
- submission: `SubmissionStatusMachine` đủ mọi transition cần thiết (kể cả `FINALIZED→REVIEW_REQUIRED`, `REVIEW_REQUIRED→COMPLETED/FINALIZED/GRADING/READY_FOR_GRADING`); internal `/status` + `/finalize` (T20) đã có; `anonymization-issues` (T27), `retry` (T17–T19), replace-file (T24/T25) đã xong.
- exam-grading: `bulkAssign` (nhưng **chưa** check trạng thái submission, chưa chuyển READY_FOR_GRADING→GRADING, chưa SINGLE_REVIEW), `discardOpenWork`, `attempt-summary`, purge detail.
- common: `outbox` package chỉ có `InMemoryOutbox`/`OutboxRelay`/`OutboxPublisher` — **chưa có `outbox_event` table ở service nào, chưa wire JDBC outbox** (§12 chưa làm).

**Còn thiếu (gap):**
1. exam-grading: toàn bộ grading attempt lifecycle — `GET context`, `PUT scores`, `POST confirm`, `is_regrade`/`attempt_no`/`rubric_id`, SINGLE_REVIEW + `reviewId`, `grading-snapshot`, `assignments/check`, `attempts-confirmed` → Result, `grader-flag` → Result, đồng bộ status READY_FOR_GRADING→GRADING sau bulk assign.
2. result-review: tạo Review `GRADER_FLAG` (kèm merge note D-21), `SCORE_DIFFERENCE`, `REMARK`; đánh giá §9.1 khi attempt confirmed; T26 (regrade confirmed → Review về OPEN); public `remark`/`withdraw`/`finalize`/`reviews`(GRADER); cửa sổ remark §9.5 + `retention_hold`; finalize saga §9.2 versioning; review detail đầy đủ (attempts GK/CL + graderId + anonymizedFileAccessUrl + allowedDecisions); outbox §12.
3. Gateway: route + role cho các path `/api/v1/submissions/{id}/{remark,reviews,assignments,finalize}` (hiện catch-all gửi hết về submission-service, POST chỉ cho EO — GRADER bị chặn `/reviews`).
4. Schema: các cột §5 còn thiếu (xem TICKET R01).

---

### TICKET R00: Grading Attempt Lifecycle (PREREQUISITE — exam-grading-service)

**Mức độ ưu tiên:** Cao — chặn mọi ticket sau
**Dependencies:** Không có (độc lập với review)
**Ước tính:** 12-16 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN
**Luồng liên quan:** Chấm (attempt lifecycle) — nền cho GRADER_FLAG/SCORE_DIFFERENCE/REMARK/REGRADE

#### Task con:
1. Migration exam-grading: `grading_assignment.review_id uuid NULL`; `grading_attempt` thêm `attempt_no int`, `is_regrade boolean DEFAULT false`, `rubric_id uuid`; `question_score` đã có `needs_review`/`note`.
2. `GET /api/v1/grading/assignments` — join Identity `users/lookup`? Không: trả `pseudonym` qua Identity `POST /internal/v1/pseudonyms/lookup` (E-08); grader chỉ thấy bài mình được giao.
3. `POST /api/v1/grading/assignments/{id}/attempts` — đã có `startAttempt`; bổ sung `attemptNo` tăng dần, `is_regrade` khi assignment có `review_id`, ghim `rubric_id` = rubric ACTIVE (D-07 nhãn CL1.. cho lượt regrade — tính khi trả context/list).
4. `GET /api/v1/grading/attempts/{attemptId}/context` — `{attemptId,submissionId,pseudonym,anonymizedFileAccessUrl,questions,rubric}`; anonymized URL lấy qua Submission `POST /internal/v1/submissions/{id}/files/ANONYMIZED/access` (đã có, HMAC). Lượt regrade **không** trả điểm/note lượt cũ (FR-024).
5. `PUT /api/v1/grading/attempts/{attemptId}/scores/{questionId}` — upsert `question_score`; validate `score ∈ [0, maxScore]` → 422 `SCORE_OUT_OF_RANGE`; `needsReview=true` **kèm note không rỗng** → gọi Result `POST /internal/v1/submissions/{id}/reviews/grader-flag` `{graderId,attemptId,reason=note}` (Q-04/ADR-018, idempotency `attemptId:flag:<questionId>`); DRAFT đã CONFIRMED → 409.
6. `POST /api/v1/grading/attempts/{attemptId}/confirm` — đủ required questions → `CONFIRMED`, `total_score` = Σ, `confirmed_at`; replay → 200 kết quả cũ; sau commit gọi Result `POST /internal/v1/submissions/{id}/attempts-confirmed` `{submissionId,attemptId}` idempotent theo attemptId (§9.1 trigger). **Dùng outbox** (xem R03) hoặc tối thiểu retry.
7. `POST /api/v1/submissions/{submissionId}/assignments` SINGLE_REVIEW (path thuộc exam-grading — cần gateway route riêng, xem R05): body `{graderId, mode:"SINGLE_REVIEW", regrade:bool, reviewId?}`; reviewId bắt buộc khi bài REVIEW_REQUIRED và phải `ACTION_AUTHORIZED(REGRADE)` — probe Result `GET /internal/v1/reviews/{id}/state` (đã có); tạo assignment `review_id`, `is_regrade=true`; sau đó outbox → Submission `REVIEW_REQUIRED→GRADING` (T15b).
8. Bulk assign: sau khi tạo assignments, outbox → Submission `READY_FOR_GRADING→GRADING` từng bài (`expectedStatus`, idempotent) — hiện `bulkAssign` chưa làm bước này (D-11/T12).
9. Internal `GET /internal/v1/submissions/{id}/grading-snapshot` — `{examId,crossGradingEnabled,graderCount,threshold,attempts:[{attemptId,graderId,totalScore,confirmedAt,questionScores}]}`; chỉ result-review-service (thêm vào allowlist caller).
10. Internal `GET /internal/v1/grading/submissions/{id}/assignments/check?graderId=` — `{assigned:boolean}` cho Result kiểm trước khi tạo GRADER_FLAG public.
11. Nhãn GK/CL: assignment thường → `GK{n}` theo thứ tự tạo (D-07); assignment có `review_id`/attempt `is_regrade` → `CL{n}`.
12. Unit + IT tests.

**Acceptance Criteria:**
- Grader chấm đủ câu → confirm → Result nhận `attempts-confirmed` → submission COMPLETED/REVIEW_REQUIRED đúng §9.1.
- `needsReview`+note → Review GRADER_FLAG mở (hoặc gộp note).
- SINGLE_REVIEW với reviewId hợp lệ → assignment regrade, giám khảo không thấy điểm cũ.

---

### TICKET R01: Schema migrations (result-review + submission nếu cần)

**Mức độ ưu tiên:** Cao
**Dependencies:** Không có
**Ước tính:** 2-3 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN

#### Task con:
1. result-review migration mới:
   - `review` thêm `previous_status varchar(30)`, `regrade_attempt_id uuid`, `opened_by uuid` (đã có `created_by` — quyết định dùng một trong hai, khuyến nghị giữ `created_by` và map `opened_by`→`created_by`, hoặc thêm `opened_by` riêng nếu spec phân biệt; **cần xác nhận** — xem §1.2 style).
   - `review_note` thêm `author_id uuid`, `author_label varchar(10)`; đổi `note`→`text`? Spec §5: `text text NOT NULL` — hiện cột tên `note`; giữ `note` hoặc rename, miễn API map đúng.
   - `council_decision` thêm `decided_by uuid`, `decision_source varchar(10)` (`COUNCIL|REMARK`, chỉ SET_FINAL_SCORE).
   - `final_result` thêm `superseded_at timestamptz`; unique `(submission_id,version)` đã có một phần — kiểm `UNIQUE(submission_id,version)`.
   - Index/partial unique `uq_review_one_unclosed` đã có (`one_open_review_per_submission`) — đổi tên không bắt buộc.
2. submission-service: kiểm `submission` đã có `binding_released`, `current_file_version`, `finalized_at`, `retention_deadline` — đã có; **không cần migration mới** trừ khi thiếu cột audit cho remark (đánh giá khi implement).
3. exam-grading: migration nằm ở R00 task 1.
4. Test Flyway migrate trên DB thật (`result_review_it` @5435).

**Acceptance Criteria:** Schema khớp §5 spec 10; không phá data hiện có.

---

### TICKET R02: result-review — Review creation mở rộng + §9.1 evaluation

**Mức độ ưu tiên:** Cao
**Dependencies:** R00 (attempts-confirmed), R01
**Ước tính:** 10-12 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN
**Luồng liên quan:** Review (GRADER_FLAG merge D-21, SCORE_DIFFERENCE T14) + Chấm (§9.1)

#### Task con:
1. `InternalReviewController`: tách `createReview` theo `reasonType`:
   - `ANONYMIZATION` — giữ nguyên (đã có).
   - `GRADER_FLAG` — `{graderId, attemptId, reason}`; đã có Review chưa đóng → **gộp** `review_note` (`author_id=graderId`, `author_label=GKn/CLn`), trả 200 `{reviewId,merged:true}` (D-21/E-16); chưa có → tạo OPEN `opened_by=graderId` (T14). Idempotency `attemptId:flag:<questionId>` qua `idempotency_record`.
   - `SCORE_DIFFERENCE` — chỉ Result tự tạo (§9.1), `opened_by`=system UUID.
   - `REMARK` — chỉ qua public `/remark` (R04), không mở internal chung.
2. Internal `POST /internal/v1/submissions/{id}/attempts-confirmed` `{submissionId,attemptId}` — idempotent theo attemptId; gọi §9.1:
   - Lấy `grading-snapshot` từ exam-grading (client mới, HMAC `result-review-service`).
   - Có Review chưa đóng → dừng (Q-05).
   - Đủ lượt CONFIRMED (cross: `graderCount`; không cross: 1), lượt `is_regrade` không tính → mọi cặp `|ti−tj| ≤ threshold` (BigDecimal) → outbox → Submission `GRADING→COMPLETED` (T13); có cặp vượt → tạo Review `SCORE_DIFFERENCE` + outbox `GRADING→REVIEW_REQUIRED` reasonCode `SCORE_DIFFERENCE` (T14).
   - Chưa đủ → 200 no-op.
3. T26: khi attempt `is_regrade` CONFIRMED → Review `ACTION_AUTHORIZED(REGRADE)` về `OPEN`, gán `regrade_attempt_id`; chỉ còn `SET_FINAL_SCORE` sau đó (§3.2 quy tắc 4 — đã có check trong `decide()`).
4. `GET /reviews/{id}` nâng cấp đúng §4.1: thêm `technicalCode`, `detail`, `allowedDecisions[]` (theo ma trận §3.2), `authorizedAction`, `previousStatus`, `regradeAttemptId`, `attempts[]` (label GK/CL, graderId, totalScore, questionScores, notes — dữ liệu từ grading-snapshot + exam-grading), `anonymizedFileAccessUrl` (gọi Submission `files/ANONYMIZED/access`, 409 FILE_NOT_READY → null), `notes[]` kèm `authorLabel`. COUNCIL không thấy danh tính; không trả ORIGINAL. `GET /reviews` thêm lọc `reasonType`, phân trang cursor (D-06), mặc định OPEN trước.
5. `review_note` endpoint nội bộ hoặc qua grader-flag path — gộp note đã ở task 1; public không cần `POST /reviews/{id}/notes` (frontend-demo có nhưng spec không định nghĩa public — **xác nhận**).
6. Unit + IT.

**Acceptance Criteria:**
- Confirm đủ lượt hợp lệ → COMPLETED; chênh > threshold → Review SCORE_DIFFERENCE OPEN.
- GRADER_FLAG khi đã có Review → merged note, không tạo review thứ hai.
- Review detail trả đủ attempts/allowedDecisions/anonymizedFileAccessUrl.

---

### TICKET R03: Transactional outbox §12 (common JDBC + wire vào result-review/exam-grading/submission)

**Mức độ ưu tiên:** Cao — spec §12 ghi "bắt buộc" cho các luồng T13–T16, T20–T23, attempts-confirmed, grader-flag
**Dependencies:** R01 (có thể song song)
**Ước tính:** 8-10 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN

#### Task con:
1. Migration `outbox_event` (§12 DDL) cho result-review, exam-grading, submission (submission cần cho release-binding nếu chưa có — kiểm tra hiện tại đang gọi `releaseBinding` đồng bộ best-effort trong cleanup job).
2. `common`: `JdbcOutboxPublisher` (insert PENDING trong cùng transaction) + `OutboxRelayJob` (`@Scheduled` `OUTBOX_RELAY_INTERVAL_MS=2000`, `FOR UPDATE SKIP LOCKED`, backoff 5s→30s→2m→10m→30m, `OUTBOX_MAX_ATTEMPTS=20` → DEAD + metric `outbox_dead_total`, thứ tự theo `aggregate_id`, 4xx-nghiệp-vụ → DEAD trừ ILLEGAL_TRANSITION-khi-đã-đúng → SENT). Hiện common chỉ có `InMemoryOutbox` — cần bản JDBC dùng `JdbcTemplate` của từng service.
3. `POST /internal/v1/outbox/{eventId}/replay` mỗi service (service-signature only) cho event DEAD.
4. Wire các luồng §12:
   - Result → Submission `/status`: T13/T14 (evaluation), T15a/T16/T23 (decision đóng), T20/T21/T22 (finalize/remark/withdraw).
   - Exam&Grading → Result: `attempts-confirmed`, `grader-flag` (ghi cùng transaction với QuestionScore/Attempt).
   - Exam&Grading → Submission `/status`: bulk T12, SINGLE_REVIEW T15b.
   - Submission → Identity release-binding: T03/T04 (nếu chuyển từ sync sang outbox).
5. Phía nhận idempotent: Submission `/status` đã idempotent theo đích+expectedStatus; Result endpoints cần `idempotency_record`/`Idempotency-Key` — đã có bảng, cần dùng.
6. Reconcile dự phòng §12: Result `reconcile-grading` 15 phút — cần Submission `GET /internal/v1/submissions?status=GRADING&updatedBefore=` (**hiện thiếu**, thêm vào scope submission-service).
7. Unit + IT.

**Acceptance Criteria:** Kill service B → A vẫn commit, relay gửi lại đúng thứ tự, không mất event; replay DEAD hoạt động.

> **Phương án thay thế nếu muốn giảm scope:** giữ gọi đồng bộ + retry như hiện tại (ResultClient/PurgeClient) và chỉ implement outbox cho các luồng §12 bắt buộc. Quyết định cần chủ dự án — đề xuất làm đúng spec.

---

### TICKET R04: result-review — Public remark/withdraw/finalize/reviews + cửa sổ §9.5

**Mức độ ưu tiên:** Cao
**Dependencies:** R02, R03 (outbox), R01
**Ước tính:** 10-12 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN — **đây là TICKET 11 của UPLOAD_COMPLETION_PLAN, mở rộng đầy đủ**
**Luồng liên quan:** Phúc khảo T21–T23, T16, finalize T20 (§9.2/§9.5)

#### Task con:
1. `POST /api/v1/submissions/{id}/remark` (EO): `{reason}` bắt buộc → 422 `REASON_REQUIRED`; submission `COMPLETED`/`FINALIZED` trong cửa sổ:
   - FINALIZED: conditional update `final_result SET retention_hold=true WHERE is_current AND purge_status='PENDING' AND retention_deadline>now AND retention_hold=false` — 0 dòng → 409 `REMARK_WINDOW_CLOSED` (`purge_status∈{IN_PROGRESS,PURGED,FAILED}` → 409 `PURGED_DATA_UNAVAILABLE`); `REMARK_WINDOW_DAYS>0` check `first_finalized_at` (Q-14, config `app.remark.window-days`, `app.remark.allow-after-finalized`).
   - Tạo Review `REMARK` OPEN (`previous_status`, `reason`, `created_by`=JWT sub) **trong cùng transaction với hold** → outbox → Submission `COMPLETED|FINALIZED→REVIEW_REQUIRED` (T21/T22, reasonCode `REMARK`). Đã có Review chưa đóng → 409 `REVIEW_ALREADY_OPEN`.
2. `POST /api/v1/reviews/{id}/withdraw` (EO): chỉ REMARK chưa `SET_FINAL_SCORE` → Review `RESOLVED(WITHDRAWN)` + outbox → Submission về `previous_status` (T23); hủy assignment/lượt regrade chưa CONFIRMED qua exam-grading (endpoint hủy assignment đã có `DELETE /api/v1/exams/{id}/assignments/{id}`? — kiểm, nếu thiếu thêm internal `discard-regrade` tương tự discard-open-work nhưng chỉ phạm vi review).
3. `POST /api/v1/submissions/{id}/finalize` (EO, `{confirm:true}`): 409 `OPEN_REVIEW`/`INSUFFICIENT_GRADING`/`INVALID_STATE`:
   - Submission `COMPLETED`, không Review chưa đóng, đủ lượt → xác định nguồn: `CouncilDecision SET_FINAL_SCORE` mới nhất (`decision_source`=`COUNCIL` hoặc `REMARK`) → else cross mean → else single.
   - Snapshot identity (Identity `GET /internal/v1/submissions/{id}/identity`) + exam core (exam-grading `GET /internal/v1/exams/{id}/core`, `/scale` — **cần thêm**, xem 05 line 19).
   - `finalized_at=now`, `retention_deadline=+1 năm lịch UTC`; FinalResult `version=1,is_current` lần đầu; **sau phúc khảo**: 1 transaction — bản cũ `is_current=false,superseded_at=now`, insert `version+1` (`is_current`, `retention_hold=false`, **copy nguyên `retention_deadline` từ v1**, D-15).
   - Outbox → Submission `/finalize` (T20; submission endpoint đã idempotent E-10). Lỗi giữa chừng → retry an toàn theo `submissionId+version`.
4. `POST /api/v1/submissions/{id}/reviews` public (GRADER `GRADER_FLAG` / không cho REMARK — REMARK chỉ qua `/remark`): verify grader được giao qua exam-grading `assignments/check`; tạo/gộp như R02 task 1; outbox → `GRADING→REVIEW_REQUIRED` (hoặc 202 `statusSync:PENDING` nếu sync lỗi — với outbox thì không cần 202).
5. `GET /api/v1/exams/{id}/results` đã có — bổ sung join identity (candidateNumber/fullName/className/pseudonym) cho bản `is_current` khi chưa purge; sau purge chỉ cột lõi (§9.4/9.5.6). Export CSV/XLSX đầy đủ cột §9.4 (hiện export chỉ 4 cột — nâng cấp hoặc tách ticket; **đề xuất tách** vì độc lập).
6. Decision flow mở rộng: `decide()` đã có ma trận — bổ sung: sau `REGRADE` + regrade CONFIRMED (T26) chỉ `SET_FINAL_SCORE`; `SET_FINAL_SCORE` trên REMARK/SCORE_DIFFERENCE → T16: lưu `decision_source`, outbox → `REVIEW_REQUIRED→COMPLETED` reasonCode `REVIEW_RESOLVED` (kèm `finalScore`? submission status endpoint không cần điểm — FinalResult finalize tính lại từ CouncilDecision mới nhất, §9.2).
7. Metric `review_open_age_hours`, `retention_hold_age_days` (§3.2/§9.3) — gauge đơn giản.
8. Unit + IT.

**Acceptance Criteria:**
- Remark trong/ngoài cửa sổ đúng 409; hold set/gỡ đúng; withdraw/DISMISS về `previous_status`.
- REGRADE → chấm lại → SET_FINAL_SCORE → finalize → FinalResult v2, `retention_deadline` không đổi.
- `finalize` idempotent, không tạo version thừa.

---

### TICKET R05: Gateway routes + OpenAPI + phân quyền

**Mức độ ưu tiên:** Cao (chặn E2E)
**Dependencies:** R02–R04 (biết path cuối)
**Ước tính:** 3-4 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN

#### Task con:
1. `application.yml` gateway — thêm route **trước** catch-all `/api/v1/submissions/**`:
   - `POST /api/v1/submissions/*/remark`, `POST /api/v1/submissions/*/finalize`, `POST /api/v1/submissions/*/reviews` → result-review-service (`RESULT_REVIEW_URL`).
   - `POST /api/v1/submissions/*/assignments` → exam-grading-service.
   - Lưu ý Spring Cloud Gateway: predicate `Path` match theo thứ tự khai báo — đặt các route cụ thể trên route `submission` chung.
2. `GatewaySecurityConfig`:
   - `POST /api/v1/submissions/*/reviews` → `hasAnyRole("GRADER","EXAM_OFFICE")` (GRADER flag; REMARK bị cấm ở service).
   - `POST /api/v1/reviews/*/withdraw` → `hasRole("EXAM_OFFICE")` — **hiện thiếu**, rơi vào `anyExchange().authenticated()` (GRADER gọi được!).
   - `POST /api/v1/submissions/*/remark|finalize` → đã được `POST /api/v1/submissions/** → EXAM_OFFICE` cover, nhưng kiểm thứ tự matcher (rule cụ thể phải đứng trước rule chung nếu khác role).
   - `PUT /api/v1/grading/**` hiện chỉ EXAM_OFFICE (line 39) — **conflict**: `PUT /api/v1/grading/attempts/{id}/scores/{qid}` là của GRADER. Sửa: `PUT /api/v1/grading/attempts/**` → GRADER; giữ PUT exams/grading khác cho EO.
   - `POST /api/v1/grading/assignments/*/attempts`, `POST /api/v1/grading/attempts/*/confirm` → GRADER (hiện `anyExchange().authenticated()` — cần rule tường minh).
3. OpenAPI: `contracts/openapi/*.yaml` cập nhật mọi endpoint mới (spec §0 quy tắc).
4. Kiểm `/internal/**` vẫn 404 qua gateway (`InternalPathNotFoundFilter` đã có).

**Acceptance Criteria:** GRADER gọi được scores/confirm/flag; COUNCIL chỉ decision; EO remark/withdraw/finalize/assignments; sai role → 403.

---

### TICKET R06: Reconcile + metrics + dọn dẹp

**Mức độ ưu tiên:** Trung bình
**Dependencies:** R02, R03, R04
**Ước tính:** 3-4 giờ
**Trạng thái:** ⬜ CHƯA THỰC HIỆN

#### Task con:
1. Result `reconcile-grading` job 15 phút (§12): `GET /internal/v1/submissions?status=GRADING&updatedBefore=now-15m` (submission — thêm endpoint phân trang nội bộ nếu thiếu) → đánh giá lại §9.1 khi đủ CONFIRMED và chưa có Review chưa đóng; metric `reconcile_fixed_total`.
2. Review aging metrics + alert hooks (`review_open_age_hours`, `retention_hold_age_days`).
3. Review detail purge đã có trong RetentionScheduler — kiểm review của submission đã purge không bị orphan (đã xóa theo submission_id — OK).
4. Docs: cập nhật `ASSUMPTIONS.md` các [CẦN XÁC NHẬN] phát sinh (xem Notes).

---

## Thứ tự thực hiện khuyến nghị

```
R01 (schema) ──→ R00 (grading lifecycle) ──→ R02 (review creation + §9.1)
                                            ├─→ R03 (outbox) ──→ R04 (remark/finalize)
R05 (gateway) ── song song sau khi chốt path ──→ R06 (reconcile/metrics)
```

## Tổng thời gian ước tính

- R00: 12-16h | R01: 2-3h | R02: 10-12h | R03: 8-10h | R04: 10-12h | R05: 3-4h | R06: 3-4h
- **Tổng: ~48-61 giờ** (chưa kể grading lifecycle nếu tách plan riêng)

## Câu hỏi cần chủ dự án xác nhận (đưa vào ASSUMPTIONS.md)

| # | Vấn đề | Đề xuất mặc định |
|---|---|---|
| Q-R1 | `POST /api/v1/submissions/{id}/reviews` public cho GRADER — gateway route về result-review; spec 05 ghi "Review được ghi trước, sau đó yêu cầu Submission chuyển status" → 202 `statusSync:PENDING` có còn cần nếu đã có outbox? | Bỏ 202, outbox đảm bảo; giữ response 201 `{reviewId,status:"OPEN"}` |
| Q-R2 | `review.opened_by` vs `created_by` — spec §5 có `opened_by varchar(100)` (04) và `created_by uuid` (10); code đang có `created_by uuid` | Dùng `created_by` cho mọi actor (worker=system UUID, GRADER=graderId, EO=JWT sub); không thêm `opened_by` |
| Q-R3 | Outbox §12 làm đầy đủ cho cả submission→identity release-binding (hiện đang sync best-effort)? | Chỉ làm cho các luồng spec liệt kê trong bảng §12; release-binding giữ nguyên cơ chế hiện tại + reconciler đã có |
| Q-R4 | `POST /reviews/{id}/notes` public có trong spec không? | Không — chỉ gộp note qua grader-flag; nếu cần thêm sau |
| Q-R5 | Export §9.4 đầy đủ (XLSX, mọi cột GK) nằm trong scope plan này? | Tách ticket riêng — độc lập với remark |

## Notes

- Mọi logic thời gian dùng `Clock` inject; không service nào query DB service khác; không PII/phách/điểm câu trong log.
- `review.version` khóa lạc quan D-20: `decide()` hiện update không check version — bổ sung `WHERE version=?` + body `version?` hoặc giữ check `status='OPEN'` (hiện tại đủ vì chỉ OPEN nhận quyết định, nhưng concurrent 2 request cùng đọc OPEN có thể cả hai pass — cần `UPDATE ... WHERE status='OPEN'` trả 0 → 409; **đã đúng một phần**, rà soát lại khi implement R02).
- COUNCIL `decision` hiện chưa check `finalScore ≤ Exam.scale` (chỉ `>= 0`) — cần gọi exam-grading `/internal/v1/exams/{id}/scale` (05 line 19, **chưa có endpoint này** — thêm vào R00 hoặc R04).
- `GRADER` hiện không đọc được `/api/v1/submissions/**` (gateway EO-only) — đúng spec E-06; grader dùng `/api/v1/grading/**`.
