# 10. Implementation Spec (Addendum cho coding agent)

> Phiên bản: 1.3 | Ngày: 2026-10-03 | Trạng thái: draft, cần chủ dự án xác nhận mục §1.2
> v1.5: **Phòng khảo thí mở lại Review ẩn danh** khi bài đã `READY_FOR_GRADING` mà vùng che thiếu (D-24, §4.6, T27/T28), cho phép xử lý lại bản ẩn danh đã có (ghi đè) và đổi template khi xử lý lại.
> v1.4: thêm xem trước vùng che bằng **bài mẫu** (`POST /paper-templates/preview`, §4.5) để chỉnh template trước khi kỳ thi ACTIVE.
> v1.3: **Hội đồng quyết định mọi loại Review** (EO chỉ tạo yêu cầu phúc khảo và thực thi), retention **không tính lại** sau phúc khảo, vá 4 lỗ hổng (một Review chưa đóng/bài, binding thí sinh, allowlist `/storage`, ảnh ẩn danh lossless), thêm `anonymization-preview` (§4.5).
> v1.2: thêm endpoint **thay file scan** (D-19, §4.4).
> v1.1: chốt phúc khảo sau FINALIZED, phân quyền xử lý review (EO: kỹ thuật/phúc khảo; Hội đồng: chỉ SCORE_DIFFERENCE), thêm bảng `users` + `graders` (xem `13-user-table.md`), chốt RabbitMQ có thêm queue `validate`.
> Phạm vi: **backend** (Gateway + 4 service + RabbitMQ + PostgreSQL + MinIO + Docker Compose). Không gồm Web Client.

## Trạng thái tài liệu

Tài liệu này đã ở mức sẵn sàng để làm việc với coding agent, với điều kiện: các mục trong §1.2 được coi là mặc định phòng khi chủ dự án chưa chốt. Khi không có quyết định chính thức, giá trị mặc định ở §1.2 được áp dụng và phải được ghi trong docs/ASSUMPTIONS.md cùng với lý do và bối cảnh.

Tài liệu này là nguồn chính cho implementation và acceptance test, và phải được coi là “ready-for-agent” sau khi các quyết định ở bảng dưới đây được xác nhận hoặc dùng mặc định.

## 0. Cách dùng và thứ tự ưu tiên

Khi các tài liệu mâu thuẫn, áp dụng thứ tự sau (cao → thấp):

1. `source/requirements-ver1_2.json` (FR/BR/NFR, nguồn nghiệp vụ duy nhất)
2. **File này (10)**: mục §2 Errata ghi đè các điểm sai/mơ hồ trong 02–09
3. `source/02`…`source/09`
4. Báo cáo tuần 2 (docx): chỉ để tham khảo kịch bản/luồng tuần tự; không phải nguồn ràng buộc

**Các quyết định của file này ghi đè requirements-ver1_2 / 01-overview** (đã được chủ dự án chấp thuận; khuyến nghị nâng requirements lên ver1_3 để hết mâu thuẫn). Khi mâu thuẫn với các điểm dưới đây, **file 10 thắng**:

| Quyết định | Requirements đang ghi | Ghi đè |
|---|---|---|
| D-12, D-15 | FR-024/FR-025/BR-022: retention một năm từ FINALIZED của submission; không nói phúc khảo sau FINALIZED | Cho phúc khảo sau FINALIZED trong hạn retention; **hạn retention vẫn tính từ lần FINALIZED đầu tiên, không gia hạn** |
| D-13 | FR-017/FR-024: "Phòng khảo thí/Hội đồng"; BR-021: Phòng khảo thí xử lý kỹ thuật/quy trình, không tự sửa điểm | **Hội đồng (một người đại diện) quyết định mọi loại Review**; Phòng khảo thí chỉ tạo yêu cầu phúc khảo và thực thi thao tác kỹ thuật đã được Hội đồng cho phép (retry, thay file, giao chấm lại). Phòng khảo thí vẫn không quyết định điểm |
| D-17 | Assumption và 01-overview: cấp JWT/tài khoản ngoài phạm vi | Identity Service phát hành JWT |
| D-19 | FR-006: "Một submission một PDF" | Một submission có thể có nhiều **phiên bản** ORIGINAL; bản hiện hành là một PDF; BR-013 vẫn giữ (bản cũ không bị sửa) |

Quy tắc cho agent:
- Mọi logic thời gian dùng `java.time.Clock` được inject (cần cho test hết hạn/retention/hết TTL), không gọi `Instant.now()` trực tiếp.
- Không tự bịa quyết định nghiệp vụ. Mục nào đánh dấu **[CẦN XÁC NHẬN]** thì cài theo giá trị mặc định đã ghi, đặt sau cấu hình/feature flag, và ghi vào `docs/ASSUMPTIONS.md`.
- Mọi endpoint, bảng, mã lỗi mới phải được cập nhật vào OpenAPI/Flyway tương ứng.
- Không service nào truy vấn DB của service khác. Không đưa PII/phách/điểm câu vào log.

## 1. Quyết định bổ sung

### 1.1 Đã chốt (suy ra trực tiếp từ tài liệu hiện có)

| ID | Quyết định |
|---|---|
| D-01 | Monorepo: `services/{gateway,identity,submission,exam-grading,result-review}`, `libs/common` (HMAC filter, error model, pagination, idempotency, audit), `deploy/docker-compose.yml`, `contracts/openapi/*.yaml`, `docs/`. Java 21, Spring Boot 3.x, Maven multi-module, Flyway mỗi service. |
| D-02 | Mọi endpoint `/internal/**` **không được route qua Gateway** (Gateway trả 404 cho path này). Service-to-service xác thực bằng chữ ký riêng (§6.2). |
| D-03 | Trạng thái `PURGED` chỉ tồn tại ở `FinalResult.purge_status`, **không** thêm vào `Submission.status`. Khi purge, Submission service xóa hẳn các row/object của submission đó. |
| D-04 | Số phách: 8 ký tự từ bảng `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, sinh bằng `SecureRandom`; trùng `UNIQUE(exam_id,value)` thì sinh lại, tối đa 5 lần rồi trả 500. |
| D-05 | Object key: bucket `original` và `anonymized`; key = `{examId}/{submissionId}/v{n}.pdf` với `n` là phiên bản file (bắt đầu 1, tăng khi thay file; bản ANONYMIZED cùng `n` với ORIGINAL nguồn). Key **không** chứa candidateId, tên file hay phách. |
| D-06 | Phân trang: `?limit=50&cursor=<opaque>` (max 200); response `{items:[...],nextCursor:string\|null}`. |
| D-07 | Nhãn giám khảo `GK1..GKn` gán theo thứ tự tạo assignment BULK của submission; lượt chấm lại hiển thị `CL1, CL2…`. Nhãn tính ở Exam & Grading, không lưu danh tính giám khảo trong dữ liệu gửi cho COUNCIL. |
| D-08 | Bảng bổ sung bắt buộc trong mỗi service: `audit_log`, `idempotency_record` (§5). Riêng Submission thêm `submission_event` (§4.3). |
| D-09 | Chỉ `EXAM_OFFICE` gọi được public API xem/cấp URL **ORIGINAL** và **ANONYMIZED**. GRADER và COUNCIL nhận URL ANONYMIZED nhúng trong response của grading context / review detail (do service đó gọi Submission nội bộ sau khi tự kiểm quyền). |
| D-10 | Mỗi submission **chỉ gồm 1 pseudonym hiện hành**; sinh lại phách (nếu cần) ghi `PseudonymHistory`. |
| D-11 | Bulk assignment xử lý **từng bài**: bài không READY_FOR_GRADING bị đưa vào `rejected[]`, bài hợp lệ vẫn được giao. Lỗi cấp lô (`GRADER_COUNT_MISMATCH`, grader không thuộc registry) từ chối toàn bộ request. Response 201: `{created:[...],rejected:[{submissionId,code}]}`. |
| D-12 | **Phúc khảo (REMARK) được phép sau FINALIZED**, chỉ khi dữ liệu chi tiết chưa bị purge (§9.5). Chỉ EXAM_OFFICE tạo yêu cầu phúc khảo. |
| D-13 | **Hội đồng quyết định mọi Review.** COUNCIL (một tài khoản đại diện, D-20) ra quyết định cho cả 4 loại `reason_type`: `ANONYMIZATION`, `GRADER_FLAG`, `SCORE_DIFFERENCE`, `REMARK`. EXAM_OFFICE **không** quyết định; EXAM_OFFICE tạo yêu cầu phúc khảo (`REMARK`) và **thực thi** các thao tác kỹ thuật Hội đồng đã cho phép (retry/force, thay file, giao giám khảo chấm lại), vì Hội đồng không được thấy ORIGINAL/danh tính. "Bất đồng chuyên môn" = các giám khảo chấm lệch nhau; Hội đồng thống nhất và nhập điểm cuối. Ma trận ở §3.2. |
| D-14 | Người dùng lưu ở bảng `users` (và hồ sơ giám khảo ở `graders`) trong **Identity DB**. **`users.user_id` là định danh duy nhất của người dùng ở mọi nơi**: `sub` của JWT, và `grader_id` / `decided_by` / `confirmed_by` / `changed_by` / `actor_id` ở mọi service. `graders.grader_id` chỉ là khóa nội bộ của bảng hồ sơ, **không** xuất hiện trong API hay DB của service khác. Chi tiết `13-user-table.md`. |
| D-15 | `FinalResult` có phiên bản (`version`, `is_current`). Mỗi lần FINALIZED lại sau phúc khảo tạo phiên bản mới. **`retention_deadline` và `Submission.finalized_at` chỉ đặt ở lần FINALIZED đầu tiên và không bao giờ tính lại** (phúc khảo thực tế diễn ra trong khoảng một tháng; sau một năm không còn ý nghĩa xem lại). Phiên bản mới sao chép nguyên `retention_deadline`. Phiên bản cũ giữ lâu dài vì chỉ chứa dữ liệu lõi. |
| D-17 | Identity cấp JWT khi đăng nhập (`POST /api/v1/auth/login`), vì `users.password_hash` là bắt buộc. Gateway vẫn chỉ verify JWT bằng public key/JWKS do Identity công bố. |
| D-18 | **RabbitMQ phục vụ cả VALIDATE lẫn ANONYMIZE** (queue `submission.validate.v1` và `submission.anonymize.v1`, §8). Cập nhật ADR-002: "RabbitMQ chỉ cho pipeline xử lý PDF (validate + anonymize)", không dùng làm event bus chung. |
| D-16 | Response cho COUNCIL tuyệt đối không có `userId`/họ tên giám khảo hay danh tính thí sinh, chỉ nhãn `GK1..GKn` / `CL1..CLn`. COUNCIL xem được mọi loại Review và PDF ANONYMIZED hiện hành, **không** xem ORIGINAL. |
| D-19 | **Thay file scan** (`replace-file`, §4.4): do EXAM_OFFICE **thực thi**. Điều kiện: bài `REVIEW_REQUIRED` có Review `ANONYMIZATION`/`GRADER_FLAG` ở `ACTION_AUTHORIZED` với hành động `REPLACE_FILE` (Hội đồng đã cho phép), hoặc `FAILED` với `failure_code ∈ {PDF_CORRUPT, PAGE_COUNT_INVALID}` (không cần Review). Tạo **phiên bản ORIGINAL mới**, giữ bản cũ bất biến (BR-013). Giữ nguyên submission và **phách**. Không dùng cho `REMARK`/`SCORE_DIFFERENCE` (chấm lại trên cùng bản scan). |
| D-20 | Hội đồng là một nhóm người nhưng **chỉ một người đại diện** thao tác trên hệ thống. Hệ thống không có bỏ phiếu/đa chữ ký. Có thể có nhiều tài khoản COUNCIL, nhưng mỗi Review chỉ được quyết định **một lần** bởi tài khoản đầu tiên gửi hợp lệ (khóa lạc quan `Review.version`; người sau nhận 409 `REVIEW_ALREADY_DECIDED`); `decided_by` được ghi lại. |
| D-21 | **Bất biến I1**: mỗi submission có **tối đa một Review chưa đóng** (`OPEN` hoặc `ACTION_AUTHORIZED`), đảm bảo bằng partial unique index (§5). Vì vậy trạng thái chỉ rời `REVIEW_REQUIRED` khi Review duy nhất đó đóng hoặc được thực thi; không có tình huống "hai review kéo về hai hướng". Cờ `GRADER_FLAG` mới khi đã có Review chưa đóng được **gộp** vào Review đó dưới dạng ghi chú (§3.2). |
| D-22 | BR-004 (xác nhận gán roster) được thực hiện ở **giao diện**; `POST …/submissions` nhận `candidateId` đã được người dùng xác nhận, backend không có bước xác nhận riêng. |
| D-23 | Thêm endpoint xem trước trang 1 sau khi che (`anonymization-preview`, §4.5) để EXAM_OFFICE kiểm tra vùng che có thiếu không trước khi cho chạy tiếp. |
| D-24 | **EO được mở lại vấn đề ẩn danh** của bài đã `READY_FOR_GRADING` (chưa giao chấm) khi xem trước cho thấy vùng che thiếu: tạo Review `ANONYMIZATION` / `MASK_INSUFFICIENT` (§4.6, T27). Hội đồng quyết định `REPROCESS` (dựng lại bản ẩn danh, có thể kèm template mới), `REPLACE_FILE` hoặc `DISMISS`; EO thực thi. Sau khi đã giao chấm, vấn đề lộ danh tính đi theo đường `GRADER_FLAG` (không dùng `REPROCESS`). |

### 1.2 [CẦN XÁC NHẬN] kèm giá trị mặc định agent sẽ dùng

Đã chốt bởi chủ dự án (không còn là câu hỏi mở): phúc khảo sau FINALIZED (D-12), Hội đồng quyết định mọi review (D-13, D-20), retention không gia hạn (D-15), bảng User (D-14), xác nhận gán roster ở giao diện (D-22).

> Quy tắc vận hành: nếu người quyết định chưa phản hồi, agent dùng giá trị mặc định dưới đây; giá trị đó phải được lưu vào docs/ASSUMPTIONS.md với tên biến/config tương ứng. Những mục ghi "đã chốt" không cần thêm xác nhận.

| ID | Vấn đề | Mặc định |
|---|---|---|
| Q-04 | `needsReview=true` trên điểm câu có tự chuyển REVIEW_REQUIRED không? | **Không**; chỉ là dấu hiệu hiển thị. Chỉ `POST /submissions/{id}/reviews` (có `reason`) mới tạo Review và đổi trạng thái (khớp docx §3.3, BR-018). 05 ghi khác. |
| Q-05 | Khi submission vào REVIEW_REQUIRED do GRADER_FLAG, các lượt chấm của GK khác đang dở? | Giữ nguyên DRAFT, vẫn confirm được, nhưng Result/Review **bỏ qua đánh giá** khi còn Review chưa đóng; Review đóng xong thì đánh giá lại. |
| Q-06 | Bulk assignment khi tắt chấm chéo và `graderIds` nhiều hơn 1? | Round-robin, mỗi bài đúng 1 giám khảo. Bật chấm chéo: `len(graderIds)==graderCount`, mỗi bài giao cho tất cả. |
| Q-07 | Đăng nhập (đã chốt bởi D-17) | Giữ cờ `AUTH_LOCAL_ENABLED=true`. Nếu sau này dùng IdP ngoài thì tắt cờ; `users` thành danh bạ và `password_hash` lưu giá trị ngẫu nhiên không dùng được. |
| Q-08 | Điều kiện nhận upload | Chỉ khi `Exam.status=ACTIVE`. Bulk assignment cũng yêu cầu `ACTIVE`. |
| Q-09 | Sửa template đã có submission | Không; chỉ tạo version mới. |
| Q-10 | Export khi chưa chốt hết | Không cho export nếu còn bất kỳ bài nào chưa `FINALIZED`; trả cảnh báo và chặn export. Chỉ khi tất cả bài đã `FINALIZED` mới cho phép export. Nếu còn chưa finalized: 409 `RESULTS_NOT_FINALIZED` (hoặc `EXPORT_BLOCKED_UNFINALIZED`) kèm `warning`/`unfinalizedCount` và danh sách rút gọn. |
| Q-11 | Thay file scan | **Đã chốt (D-19)**: có endpoint `replace-file`. |
| Q-13 | Thay file khi đã có lượt chấm | Đang có lượt `CONFIRMED` → 409 `ATTEMPTS_EXIST` (Hội đồng dùng `DISMISS`/`REGRADE` thay vì `REPLACE_FILE`). Lượt `DRAFT` và assignment đang mở bị hủy khi hoàn tất thay file; sau khi bài về `READY_FOR_GRADING`, EO phải giao lại. |
| Q-12 | Thời hạn JWT và refresh | Access token 15 phút (`JWT_TTL_MINUTES`), chưa có refresh token trong MVP. Khóa tài khoản chỉ có hiệu lực khi token hết hạn. |
| Q-14 | Giới hạn thời gian phúc khảo | `REMARK_WINDOW_DAYS=0` = cho đến hết `retention_deadline`. Thực tế phúc khảo thường trong ~1 tháng; đặt `30` nếu muốn khóa cứng (tính từ `finalized_at` đầu tiên, không vượt `retention_deadline`). Quá hạn → 409 `REMARK_WINDOW_CLOSED`. |
| Q-15 | Đổi template khi xử lý lại bản ẩn danh | Cho phép: `retry` kèm `templateId` (template tồn tại, `active`), chỉ đổi `Submission.template_id`, **không** đổi `Exam.template_id`. Template mới phải qua kiểm ±5% mọi trang. |

## 2. Errata: các điểm ghi đè 02–09

| ID | Sai/mơ hồ trong tài liệu gốc | Quy định áp dụng |
|---|---|---|
| E-01 | 05: `upload.url = .../storage/original/{objectKey}` và `fields.key = original/{objectKey}` (bucket bị lặp) | `upload.url = {PUBLIC_BASE}/storage/original` (URL cấp bucket); `fields.key = {examId}/{submissionId}/v{n}.pdf`. Gateway `StripPrefix=1`, path-style tới `minio:9000/original`. |
| E-02 | Header trạng thái "final" lẫn "draft" giữa các file | Coi 02–09 đều là baseline v2; file này là addendum. |
| E-03 | `UPLOAD_EXPIRED` bị hiểu là nguyên nhân chuyển FAILED | `UPLOAD_EXPIRED` chỉ là **409 của `/complete`**. Chuyển FAILED chỉ do scheduler với `UPLOAD_ABANDONED`, hoặc `FILE_TOO_LARGE` ở `/complete`. |
| E-04 | Báo cáo docx lúc dùng `PATCH`, lúc dùng `POST` cho `/internal/v1/submissions/{id}/status` | Dùng `POST` (theo 05). |
| E-05 | 05: GRADER gọi được `files/ANONYMIZED/access` | Xem D-09: GRADER/COUNCIL không gọi endpoint này; tránh vòng phụ thuộc Submission↔Exam&Grading. |
| E-06 | 05: `GET /submissions/{id}` "GRADER chỉ bài đã giao" | Chỉ EXAM_OFFICE. GRADER dùng `GET /grading/assignments`. SSE cũng chỉ EXAM_OFFICE. |
| E-07 | 02/03: "phủ rectangle bằng PDFBox" | **Không đủ an toàn**: với PDF scan, ảnh gốc vẫn còn bên dưới và có thể trích ra. Xem §7. |
| E-08 | 05 thiếu API tra phách theo lô cho assignment list | Thêm `POST /internal/v1/pseudonyms/lookup` (§4.2). |
| E-09 | 02 chỉ có queue `anonymize`; bước VALIDATE không có cơ chế kích hoạt | **Đã chốt (D-18)**: thêm routing key `validate` và queue riêng (§8). Cập nhật 02/03/08 tương ứng: RabbitMQ dùng cho VALIDATE + ANONYMIZE. |
| E-11 | 05: `council-decision` trả 403 "nếu review không phải bất đồng chuyên môn" | Hội đồng quyết định **mọi** loại Review qua `POST /reviews/{id}/decision` (§4.1). `council-decision` giữ làm bí danh của `decisionType=SET_FINAL_SCORE`. Chỉ các `decisionType` hợp lệ cho từng `reason_type` (§3.2), sai → 422 `DECISION_NOT_ALLOWED`. |
| E-12 | 05/04: `graderId`, `grader_id`, `decided_by`, `confirmed_by`, `changed_by` là `string/varchar(100)` | Đổi thành `uuid` = `users.user_id` (D-14). `graderIds:[string]` trong bulk assignment thành `[uuid]`. |
| E-13 | 04: `FinalResult.submission_id` suy ra 1-1 | Một submission có thể có nhiều `FinalResult` (phiên bản) sau phúc khảo (D-15). |
| E-14 | 04/05: Submission DB không lưu `candidateId`, nhưng `POST /internal/v1/pseudonyms` yêu cầu `candidateId` và được gọi ở bước VALIDATE (worker không biết `candidateId`; thay file scan cũng cần lấy lại đúng phách) | Tại T01 (init), Submission gọi Identity `POST /internal/v1/submission-bindings` `{examId,submissionId,candidateId}` (Identity kiểm candidate thuộc exam, lưu mapping candidate↔submission, idempotent). Từ đó `POST /internal/v1/pseudonyms` chỉ nhận `{examId,submissionId,idempotencyKey}`; Identity tra candidate theo binding. Gọi lại cùng `idempotencyKey` trả về **cùng** phách. |
| E-15 | 04: `Review.status` là `OPEN` hoặc `RESOLVED`, `resolution_type` là `CONTINUE`, `REGRADE` hoặc `COUNCIL_DECISION`, `CouncilDecision.final_score` bắt buộc | `Review.status` thêm `ACTION_AUTHORIZED`; `resolution_type` = `decisionType` đã đóng Review hoặc `WITHDRAWN`; `CouncilDecision` có `decision_type`, `final_score` chỉ bắt buộc với `SET_FINAL_SCORE` (§5). |
| E-16 | 05: `POST /submissions/{id}/reviews` luôn tạo Review mới | Khi đã có Review chưa đóng: `GRADER_FLAG` được **gộp** (200 `{reviewId,merged:true}`, thêm ghi chú); loại khác → 409 `REVIEW_ALREADY_OPEN` (D-21). |
| E-17 | 05: `retry` body `{step}`; `POST /submissions/{id}/assignments` `{graderId,mode,regrade}` | Thêm `force?` và `reviewId?` vào `retry`, thêm `reviewId` vào assignment SINGLE_REVIEW; khi bài `REVIEW_REQUIRED` bắt buộc có `reviewId` đang `ACTION_AUTHORIZED` đúng hành động, ngược lại 409 `REVIEW_NOT_AUTHORIZED` (§4.1). |
| E-10 | 05 `finalize` response không nói rõ idempotent | `finalize` gọi lại khi đã FINALIZED trả 200 cùng `resultId` (không phải 409), trừ khi bài đã purge. |

## 3. Máy trạng thái

### 3.1 Submission (chủ sở hữu: Submission Service; mọi transition qua `POST /internal/v1/submissions/{id}/status` với `expectedStatus` + optimistic `version`)

| # | From → To | Người kích hoạt | Điều kiện (guard) | Side effect |
|---|---|---|---|---|
| T01 | (new) → UPLOADING | EXAM_OFFICE `POST …/submissions` | Exam ACTIVE; candidate thuộc exam; `fileSizeBytes ≤ 52428800`; `contentType=application/pdf`; candidate **không có binding `ACTIVE`** (§5.2) | **Thứ tự**: (1) insert Submission `UPLOADING`, `upload_expires_at=now+10m`, commit (2) gọi Identity `submission-bindings`; lỗi 409 → xóa row vừa tạo và trả lỗi (3) presigned POST, ghi `submission_event`/SSE. Hỗ trợ `Idempotency-Key`. Crash giữa (1) và (2) → scheduler đưa row về `FAILED/UPLOAD_ABANDONED` khi hết hạn |
| T02 | UPLOADING → UPLOADED | EXAM_OFFICE `/complete` | Chưa hết hạn; HEAD tồn tại; size hợp lệ | Ghi `ExamFile ORIGINAL` (sha256 null); tạo `ProcessingJob(VALIDATE)` và publish |
| T03 | UPLOADING → FAILED (`UPLOAD_ABANDONED`) | Scheduler Submission | `now > upload_expires_at` | SSE; **release binding** (outbox §12, idempotent) |
| T04 | UPLOADING → FAILED (`FILE_TOO_LARGE`) | `/complete` | Size thực tế vượt 50 MB | Xóa object, SSE, **release binding** |
| T05 | UPLOADED → VALIDATING | Worker VALIDATE | Nhận job | `progress` cập nhật |
| T06 | VALIDATING → ANONYMIZING | Worker ANONYMIZE | Worker VALIDATE đã xong: PDF đọc được, 1–20 trang, đúng ±5% template, Identity đã sinh phách, job `anonymize` đã publish. Status đổi khi worker ANONYMIZE **nhận job** | VALIDATE ghi `sha256`, `page_count` |
| T07 | VALIDATING → FAILED | Worker VALIDATE | PDF hỏng/không đọc được | `failure_code=PDF_CORRUPT` |
| T08 | VALIDATING → REVIEW_REQUIRED | Worker VALIDATE | Kích thước trang lệch quá 5% (bỏ qua nếu job có `force=true`) | Tạo Review `ANONYMIZATION` với `technical_code=TEMPLATE_SIZE_MISMATCH`, `detail={pageNumber,deviationPercent}` (chưa có phách) |
| T09 | ANONYMIZING → READY_FOR_GRADING | Worker ANONYMIZE | Ghi bản ANONYMIZED thành công | Ghi `ExamFile ANONYMIZED` |
| T10 | ANONYMIZING → FAILED | Worker ANONYMIZE | Lỗi kỹ thuật, đã hết 3 retry | `failure_code=ANONYMIZE_RETRY_EXHAUSTED` |
| T11 | ANONYMIZING → REVIEW_REQUIRED | Worker ANONYMIZE | Lỗi template (không retry), PDF vẫn đọc được | Tạo Review `ANONYMIZATION` với `technical_code=TEMPLATE_MASK_ERROR`, `detail` |
| T12 | READY_FOR_GRADING → GRADING | Exam & Grading (bulk assignment) | Tất cả bài đều READY_FOR_GRADING | |
| T13 | GRADING → COMPLETED | Result/Review | Đủ lượt CONFIRMED theo cấu hình (không tính lượt `is_regrade`); **không có Review chưa đóng**; (cross) mọi cặp ≤ threshold | |
| T14 | GRADING → REVIEW_REQUIRED | Result/Review | `GRADER_FLAG` (GRADER gọi `POST …/reviews`, không có Review chưa đóng) hoặc cặp lệch > threshold (Review `SCORE_DIFFERENCE`). Tạo Review trước, rồi đổi status | Review mới là Review chưa đóng duy nhất (D-21) |
| T15 | REVIEW_REQUIRED → GRADING | Result/Review hoặc Exam & Grading | (a) Hội đồng `DISMISS` cho `GRADER_FLAG` → Review `RESOLVED`; hoặc (b) Hội đồng cho phép `REGRADE` (Review `ACTION_AUTHORIZED`) và EO tạo SINGLE_REVIEW assignment có `reviewId` | (a): đánh giá lại ngay §9.1 (có thể sang T13). (b): lượt chấm lại `is_regrade=true`, Review vẫn `ACTION_AUTHORIZED` |
| T16 | REVIEW_REQUIRED → COMPLETED | Result/Review | Hội đồng `SET_FINAL_SCORE` (`SCORE_DIFFERENCE`; `REMARK`; hoặc `GRADER_FLAG` sau chấm lại) | Review → `RESOLVED`; lưu `CouncilDecision`; `decision_source=COUNCIL` (REMARK → `REMARK`) |
| T17 | REVIEW_REQUIRED → VALIDATING | EO `/retry` `{step:"VALIDATE",force:true,reviewId}` | Review `ANONYMIZATION` (`TEMPLATE_SIZE_MISMATCH`) ở `ACTION_AUTHORIZED` với `FORCE_ANONYMIZE`; chưa có lượt chấm | Job `VALIDATE` `force=true` (bỏ qua ±5%; vẫn kiểm đọc được/số trang, sinh phách, publish `anonymize`); Review → `RESOLVED(FORCE_ANONYMIZE)`. EO nên xem `anonymization-preview` (§4.5) trước khi chạy |
| T18 | REVIEW_REQUIRED → ANONYMIZING | EO `/retry` `{step:"ANONYMIZE",reviewId,templateId?}` | Review `ANONYMIZATION` (`TEMPLATE_MASK_ERROR` hoặc `MASK_INSUFFICIENT`) ở `ACTION_AUTHORIZED` với `REPROCESS`; chưa có lượt chấm confirmed | Tạo job `ANONYMIZE`; với `MASK_INSUFFICIENT` job có `overwrite=true` (ghi đè bản ẩn danh cùng key, §4.6); `templateId` (nếu có) chỉ hợp lệ ở đây (Q-15). Review → `RESOLVED(REPROCESS)` |
| T19 | FAILED → VALIDATING / ANONYMIZING | EXAM_OFFICE `/retry` | `failure_code ∈ {VALIDATE_RETRY_EXHAUSTED, ANONYMIZE_RETRY_EXHAUSTED}`; step khớp bước đã lỗi. `PDF_CORRUPT`/`PAGE_COUNT_INVALID` → dùng thay file (T25), `UPLOAD_ABANDONED`/`FILE_TOO_LARGE` → tạo submission mới | |
| T20 | COMPLETED → FINALIZED | Result/Review (saga) | Không có Review chưa đóng; FinalResult snapshot đã lưu | **Lần đầu**: `finalized_at=now`, `retention_deadline=finalized_at+1 năm` (cộng 1 năm theo lịch, UTC). **Lần sau (sau phúc khảo)**: **không** đổi `finalized_at`, `retention_deadline` (D-15); gỡ `retention_hold` |
| T21 | COMPLETED → REVIEW_REQUIRED | Result/Review | EO tạo `REMARK` | Review `REMARK` OPEN, lưu `previous_status=COMPLETED` |
| T22 | FINALIZED → REVIEW_REQUIRED | Result/Review | EO tạo `REMARK`; `FinalResult` hiện hành `purge_status=PENDING` và `now < retention_deadline` (§9.5) | Đặt `retention_hold=true` cùng transaction; Review `REMARK` OPEN, `previous_status=FINALIZED` |
| T23 | REVIEW_REQUIRED hoặc GRADING → FINALIZED / COMPLETED | Result/Review | Review `REMARK` chưa có `SET_FINAL_SCORE`, và (a) EO `withdraw` (thí sinh rút), hoặc (b) Hội đồng `DISMISS` (bác phúc khảo) | Trả về `previous_status`; nếu FINALIZED thì giữ nguyên `finalized_at`, `retention_deadline`; gỡ `retention_hold`; hủy assignment/lượt chấm lại chưa CONFIRMED |
| T24 | REVIEW_REQUIRED → UPLOADED | EO `replace-file/…/complete` | Review `ANONYMIZATION`/`GRADER_FLAG` ở `ACTION_AUTHORIZED` với `REPLACE_FILE`; không có lượt `CONFIRMED`; HEAD object hợp lệ | Ghi ExamFile ORIGINAL `v n+1`; Review → `RESOLVED(REPLACE_FILE)`; Exam&Grading hủy lượt DRAFT/assignment mở; publish `validate` với `fileVersion`; sau đó T05 như thường |
| T25 | FAILED → UPLOADED | EO `replace-file/…/complete` | `failure_code ∈ {PDF_CORRUPT, PAGE_COUNT_INVALID}`; HEAD hợp lệ | Như T24, `failure_code` xóa |
| T26 | GRADING → REVIEW_REQUIRED | Result/Review | Review `GRADER_FLAG`/`REMARK` ở `ACTION_AUTHORIZED(REGRADE)` và lượt chấm lại (`is_regrade`) vừa `CONFIRMED` | Review → `OPEN` (`regrade_attempt_id` được gán), chờ Hội đồng `SET_FINAL_SCORE` (hoặc `WITHDRAWN` nếu EO rút) |
| T27 | READY_FOR_GRADING → REVIEW_REQUIRED | EO `POST …/anonymization-issues` | Bài `READY_FOR_GRADING` (nên chưa có assignment mở); có ExamFile ANONYMIZED hiện hành; không có Review chưa đóng | **Thứ tự**: tạo Review `ANONYMIZATION`/`MASK_INSUFFICIENT` `OPEN` (Result, idempotent) rồi mới đổi status; `detail={fileVersion,templateId}`, `reason` do EO nhập |
| T28 | REVIEW_REQUIRED → READY_FOR_GRADING | Result/Review | Hội đồng `DISMISS` Review `MASK_INSUFFICIENT` (bản ẩn danh được chấp nhận như hiện có) | Review → `RESOLVED(DISMISS)` |

Mọi transition khác → `409 ILLEGAL_TRANSITION`. `FINALIZED` **không còn là trạng thái cuối**: chỉ rời đi bằng T22 (phúc khảo) và quay lại bằng T20 (phiên bản mới) hoặc T23 (rút/bác). **Bài ở `REVIEW_REQUIRED` luôn có đúng một Review chưa đóng** (D-21); trong lúc chấm lại (`GRADING`) Review ở `ACTION_AUTHORIZED`. Quy tắc chung: ghi DB trước, phát SSE sau; transition lặp lại cùng đích với cùng `version` là idempotent.

### 3.2 Các máy trạng thái nhỏ

- **Exam**: `DRAFT → ACTIVE` (cần: tổng `Question.max_score = scale`, có rubric ACTIVE, có template) → `CLOSED`. Không quay lại DRAFT.
- **Rubric**: `DRAFT → ACTIVE` (cần `confirmed=true`, EXAM_OFFICE). Kích hoạt bản mới thì bản ACTIVE cũ chuyển về trạng thái `SUPERSEDED` (**thêm giá trị enum**, vì DB chỉ cho 1 ACTIVE/exam).
- **GradingAssignment**: `ASSIGNED → IN_PROGRESS` (khi tạo attempt) `→ COMPLETED` (khi confirm) ; `ASSIGNED|IN_PROGRESS → CANCELLED` (EXAM_OFFICE hủy, chỉ khi attempt chưa CONFIRMED).
- **GradingAttempt**: `DRAFT → CONFIRMED`. Sau CONFIRMED mọi PUT điểm trả 409 `INVALID_STATE`.
- **Review** (D-13, D-20, D-21): `status`: `OPEN` (chờ Hội đồng) → `ACTION_AUTHORIZED` (Hội đồng đã cho phép một thao tác, chờ EO thực thi) → `RESOLVED`. **Mỗi submission có tối đa một Review chưa đóng** (`OPEN`/`ACTION_AUTHORIZED`). Hội đồng quyết định bằng `POST /reviews/{id}/decision` với `decisionType` theo bảng; EO thực thi hành động tương ứng và chỉ khi Review đang `ACTION_AUTHORIZED` đúng hành động.

  | `reason_type` | Tạo bởi | `decisionType` Hội đồng được chọn | Kết quả |
  |---|---|---|---|
  | `ANONYMIZATION` + `TEMPLATE_SIZE_MISMATCH` | Worker (T08) | `FORCE_ANONYMIZE`, `REPLACE_FILE` | `ACTION_AUTHORIZED` → EO `retry{force}` (T17) hoặc thay file (T24) |
  | `ANONYMIZATION` + `TEMPLATE_MASK_ERROR` | Worker (T11) | `REPROCESS`, `REPLACE_FILE` | `ACTION_AUTHORIZED` → EO `retry` (T18) hoặc thay file (T24) |
  | `ANONYMIZATION` + `MASK_INSUFFICIENT` | **EO** (T27) | `REPROCESS`, `REPLACE_FILE`, `DISMISS` | `DISMISS` → `RESOLVED` + T28. Hai loại còn lại → `ACTION_AUTHORIZED` → EO `retry` (T18) hoặc thay file (T24) |
  | `GRADER_FLAG` | GRADER (T14) | `DISMISS`, `REPLACE_FILE`, `REGRADE` | `DISMISS` → `RESOLVED` + T15. Hai loại còn lại → `ACTION_AUTHORIZED` |
  | `SCORE_DIFFERENCE` | Result (T14) | `SET_FINAL_SCORE` | `RESOLVED` + T16 (`finalScore` do Hội đồng nhập, `reason` bắt buộc) |
  | `REMARK` | EO (T21/T22) | `DISMISS`, `REGRADE`, `SET_FINAL_SCORE` | `DISMISS` → `RESOLVED` + T23. `SET_FINAL_SCORE` → `RESOLVED` + T16. `REGRADE` → `ACTION_AUTHORIZED` |

  **Quy tắc quyết định:** (1) mọi quyết định bắt buộc `reason` (422 `REASON_REQUIRED`); (2) `decisionType` ngoài bảng → 422 `DECISION_NOT_ALLOWED`; (3) chỉ Review `OPEN` nhận quyết định, quyết định thứ hai → 409 `REVIEW_ALREADY_DECIDED`; (4) sau khi `REGRADE` đã chấm xong (`regrade_attempt_id` có giá trị, T26), chỉ còn `SET_FINAL_SCORE`; (5) `finalScore` ∈ [0, `Exam.scale`], ngoài → 422 `SCORE_OUT_OF_RANGE`. `resolution_type` = `decisionType` đã đóng Review, hoặc `WITHDRAWN` khi EO rút phúc khảo.

  **Gộp cờ giám khảo:** khi đã có Review chưa đóng, `GRADER_FLAG` mới được gộp thành ghi chú (`review_note`, hiển thị nhãn `GKi`) và trả 200 `{reviewId,merged:true}`; Hội đồng thấy ghi chú trong chi tiết Review.

  **Quyền xem:** COUNCIL và EO xem được mọi loại Review; COUNCIL không thấy danh tính thí sinh/giám khảo (D-16). EO thực thi hành động không có Review `ACTION_AUTHORIZED` tương ứng → 409 `REVIEW_NOT_AUTHORIZED`. Metric `review_open_age_hours` cảnh báo Review treo (Hội đồng chưa quyết, hoặc EO chưa thực thi).
- **FinalResult.purge_status**: `PENDING → IN_PROGRESS → PURGED | FAILED`; `FAILED → IN_PROGRESS` ở lần chạy tiếp theo.

## 4. API bổ sung (ngoài 05)

Quy ước: lỗi theo `{code,message,requestId,details?}`. Role viết tắt: EO=EXAM_OFFICE, GR=GRADER, CO=COUNCIL.

### 4.1 Public

| Method + Path | Role | Mô tả ngắn |
|---|---|---|
| `POST/GET /api/v1/paper-templates`, `GET /api/v1/paper-templates/{id}` | EO | Quản lý PaperTemplate (Submission). Body theo §6.4. Không có PUT (Q-09). |
| `GET /api/v1/exams`, `GET /api/v1/exams/{id}`, `PATCH /api/v1/exams/{id}` | EO | List/xem/sửa (chỉ khi DRAFT). |
| `POST /api/v1/exams/{id}/activate`, `POST /api/v1/exams/{id}/close` | EO | Chuyển trạng thái (§3.2). |
| `GET /api/v1/exams/{id}/rubrics`, `GET …/rubrics/{rubricId}` | EO | |
| `GET/POST/DELETE /api/v1/exams/{id}/graders` | EO | Bảng `exam_grader(exam_id,user_id)`. POST `{userId}`; Exam & Grading gọi Identity `users/lookup` kiểm user tồn tại, `is_active=true`, `role=GRADER` (422 `GRADER_NOT_REGISTERED`). GET trả `{userId,fullName,username}` (tra từ Identity). |
| `/api/v1/users/**`, `/api/v1/auth/login` | EO / public | Quản lý người dùng và đăng nhập: xem `13-user-table.md` §4. |
| `POST /api/v1/exams/{id}/candidates` (thêm lẻ), `PATCH/DELETE …/candidates/{cid}` | EO | Xóa bị chặn nếu đã có submission. |
| `GET /api/v1/exams/{id}/submissions?status=&cursor=&limit=` | EO | Danh sách + lọc. |
| `POST /api/v1/submissions/{id}/replace-file`, `POST …/replace-file/{replacementId}/complete`, `DELETE …/replace-file`, `GET /api/v1/submissions/{id}/files` | EO | Thay file scan, xem phiên bản file: §4.4. `files/ORIGINAL/access` nhận thêm `?version=n` (mặc định bản hiện hành); `files/ANONYMIZED/access` luôn là bản hiện hành. |
| `DELETE /api/v1/exams/{id}/assignments/{assignmentId}` | EO | Hủy assignment (§3.2). |
| `GET /api/v1/reviews?status=&reasonType=&cursor=` | EO, CO | Cả hai thấy **mọi loại** Review. CO không thấy danh tính thí sinh/giám khảo. Mặc định trả Review `OPEN` trước. |
| `GET /api/v1/reviews/{id}` | EO, CO | Chi tiết: `reasonType`, `technicalCode`, `detail`, `notes[]` (nhãn GK), các lượt chấm (nhãn GK/CL, tổng điểm, điểm câu), `allowedDecisions[]`, `authorizedAction`, `anonymizedFileAccessUrl` (bản hiện hành, có thể `null` nếu chưa tạo). Không có URL ORIGINAL. |
| `POST /api/v1/reviews/{id}/decision` | **CO** | `{decisionType, reason, finalScore?}` theo §3.2. 200 `{decisionId,reviewId,decisionType,status}` với `status` là `ACTION_AUTHORIZED` hoặc `RESOLVED`. Lỗi: 422 `REASON_REQUIRED`/`DECISION_NOT_ALLOWED`/`SCORE_OUT_OF_RANGE`, 409 `REVIEW_ALREADY_DECIDED`. `POST …/council-decision {finalScore,reason}` (05) là bí danh của `SET_FINAL_SCORE`. EO gọi → 403. |
| `POST /api/v1/submissions/{id}/retry` (05, mở rộng) | EO | Body `{step, force?, reviewId?, templateId?}`. Bài `FAILED`: như T19. Bài `REVIEW_REQUIRED`: bắt buộc `reviewId` đang `ACTION_AUTHORIZED` với `FORCE_ANONYMIZE` (→ `step=VALIDATE`, `force=true`, T17) hoặc `REPROCESS` (→ `step=ANONYMIZE`, T18); sai → 409 `REVIEW_NOT_AUTHORIZED`. `templateId` chỉ cho `REPROCESS` (Q-15): không tồn tại → 404, không `active` → 422 `TEMPLATE_INACTIVE`, dùng với hành động khác → 422. |
| `POST /api/v1/submissions/{id}/anonymization-issues` | EO | `{reason}` bắt buộc. Mở lại vấn đề ẩn danh khi bài `READY_FOR_GRADING` (T27): §4.6. 201 `{reviewId,status:"OPEN"}`; 409 `INVALID_STATE` (không phải `READY_FOR_GRADING`), 409 `REVIEW_ALREADY_OPEN`, 422 `reason` trống. Hỗ trợ `Idempotency-Key`. |
| `POST /api/v1/submissions/{id}/assignments` (05, mở rộng) | EO | `SINGLE_REVIEW` nhận thêm `reviewId` = Review `ACTION_AUTHORIZED` với `REGRADE` (T15b), `graderId` do **EO chọn** (Hội đồng không biết danh tính giám khảo). Sai → 409 `REVIEW_NOT_AUTHORIZED`. |
| `POST /api/v1/submissions/{id}/remark` | EO | `{reason}` bắt buộc. Cho phép khi submission `COMPLETED` hoặc `FINALIZED` trong cửa sổ §9.5; ngoài cửa sổ → 409 `REMARK_WINDOW_CLOSED` (đã purge: 409 `PURGED_DATA_UNAVAILABLE`); đã có Review chưa đóng → 409 `REVIEW_ALREADY_OPEN`. Chỉ **tạo yêu cầu**; Hội đồng quyết định. |
| `POST /api/v1/reviews/{id}/withdraw` | EO | Rút phúc khảo (T23). Chỉ Review `REMARK` chưa có `SET_FINAL_SCORE`; hủy các assignment/lượt chấm lại chưa confirm. |
| `POST /api/v1/submissions/{id}/reviews` (05) | GR | `{reasonType:"GRADER_FLAG",reason}` cho bài được giao. Có Review chưa đóng → gộp (§3.2). EO không dùng endpoint này cho `REMARK` (dùng `/remark`). |
| `POST /api/v1/submissions/{id}/anonymization-preview` | EO | Xem trước trang 1 sau khi che: §4.5. |
| `POST /api/v1/paper-templates/preview` | EO | Xem trước vùng che bằng PDF mẫu, không cần submission: §4.5. |
| `GET /api/v1/submissions/{id}/identity` | EO | **Ghép phách (FR-019)**: gọi Identity, trả `{candidateNumber,fullName,className,pseudonym}`. Ghi audit. |
| `GET /api/v1/exams/{id}/results?cursor=` | EO | Danh sách FinalResult đã ghép (không điểm câu sau purge). |
| `PUT /api/v1/submissions/{id}/pseudonym` | EO | Chỉnh phách thủ công, bắt buộc `reason`; ghi `PseudonymHistory`. Chỉ khi chưa FINALIZED. |

### 4.2 Internal (`/internal/v1`, chỉ service-to-service)

| Method + Path | Caller → Owner | Mô tả |
|---|---|---|
| `GET /exams/{examId}` | Submission, Result → Exam&Grading | `{examId,status,scale,templateId,crossGradingEnabled,graderCount,differenceThreshold,name,subject}` |
| `GET /paper-templates/{id}` | Exam&Grading → Submission | Kiểm template tồn tại khi tạo/activate exam. |
| `POST /submission-bindings` | Submission → Identity | `{examId,submissionId,candidateId}` → 201; gọi lại cùng `submissionId` khi binding còn `ACTIVE` → 200 (idempotent). 409 `CANDIDATE_EXAM_MISMATCH`; 409 `CANDIDATE_ALREADY_BOUND` (candidate đã có binding `ACTIVE` của submission khác); 409 `BINDING_RELEASED` (binding của chính submission này đã nhả). Chi tiết §5.2. |
| `POST /submission-bindings/{submissionId}/release` | Submission → Identity | `{reason}` (`UPLOAD_ABANDONED`/`FILE_TOO_LARGE`). Idempotent: 200 cả khi đã `RELEASED` hoặc không tồn tại. |
| `POST /pseudonyms/lookup` | Exam&Grading, Result → Identity | `{submissionIds:[uuid]}` → `{items:[{submissionId,pseudonym}]}`; **không** trả candidate. |
| `POST /users/lookup` | Exam&Grading, Result → Identity | `{userIds:[uuid]}` → `{items:[{userId,username,fullName,role,isActive}]}`. Chỉ trả cho service được allowlist; không trả `password_hash`. |
| `POST /grading/attempts-confirmed` | Exam&Grading → Result | `{submissionId,attemptId}`; idempotent theo `attemptId`; Result chạy §9.1. |
| `GET /grading/assignments/check?submissionId=&graderId=` | Result → Exam&Grading | Dùng khi GRADER gửi flag: `{assigned:boolean}`. |
| `GET /grading/submissions/{id}/attempt-summary` | Submission → Exam&Grading | `{confirmedCount,draftCount,openAssignmentCount}` (kiểm trước khi cho thay file). |
| `POST /grading/submissions/{id}/discard-open-work` | Submission → Exam&Grading | Idempotent (`Idempotency-Key=replacementId`). Hủy assignment mở, đặt lượt `DRAFT` → `DISCARDED`; trả `{discardedAttempts,cancelledAssignments}`; 409 `ATTEMPTS_EXIST` nếu có lượt `CONFIRMED`. |
| `POST /reviews` | Submission (worker hoặc `anonymization-issues`) → Result | Tạo Review `ANONYMIZATION` `{submissionId,technicalCode,detail,reason?,createdBy?}` (`technicalCode` ∈ `TEMPLATE_SIZE_MISMATCH`, `TEMPLATE_MASK_ERROR`, `MASK_INSUFFICIENT`); idempotent theo `submissionId + fileVersion + technicalCode` (+ `Idempotency-Key` nếu có). Đã có Review chưa đóng → 409 `REVIEW_ALREADY_OPEN` (worker: ack và ghi log, không đổi trạng thái; `anonymization-issues`: trả 409 cho EO). |
| `POST /submissions/{id}/files/{type}/access` | Exam&Grading, Result → Submission | Presigned GET 5 phút (service credential, người gọi đã tự kiểm quyền). |

### 4.3 SSE

- Bảng `submission_event(event_id bigserial PK, submission_id, exam_id, status, progress, created_at)`; `id:` của SSE = `event_id`.
- `Last-Event-ID` → replay các event có `event_id > N` của exam; thiếu/quá cũ (> 1 giờ) → gửi event đặc biệt `event: resync` để client gọi snapshot REST.
- Heartbeat comment mỗi 15 giây; header `X-Accel-Buffering: no`, `Cache-Control: no-cache`.
- Ghi `submission_event` **trong cùng transaction** với đổi status; phát SSE sau commit.

### 4.4 Thay file scan (D-19)

Dùng cho bài lỗi scan/mờ/thiếu trang mà EXAM_OFFICE phải quét lại. Luồng 3 bước giống upload (init → upload qua `/storage/original` → complete) nhưng **không tạo submission mới**.

**Điều kiện** (kiểm lúc `replace-file` và kiểm lại lúc `complete`):
- Submission ở `REVIEW_REQUIRED` có Review `ANONYMIZATION` hoặc `GRADER_FLAG` ở `ACTION_AUTHORIZED` với `authorized_action=REPLACE_FILE` (Hội đồng đã cho phép, D-13), **hoặc** `FAILED` với `failure_code ∈ {PDF_CORRUPT, PAGE_COUNT_INVALID}` (không cần Review). Trạng thái/Review khác → 409 `REPLACE_NOT_ALLOWED` (thiếu cho phép → 409 `REVIEW_NOT_AUTHORIZED`).
- Không có lượt chấm `CONFIRMED` (hỏi Exam & Grading `attempt-summary`) → nếu có, 409 `ATTEMPTS_EXIST` (Q-13).
- Không có `file_replacement` đang `PENDING` chưa hết hạn → 409 `REPLACEMENT_IN_PROGRESS`.

| Endpoint | Mô tả |
|---|---|
| `POST /api/v1/submissions/{id}/replace-file` | Body `{reason, fileName, fileSizeBytes, contentType:"application/pdf"}` (`reason` bắt buộc). Kiểm `fileSizeBytes ≤ 52428800`, sinh `version = current_file_version + 1`, key `{examId}/{submissionId}/v{n}.pdf`, tạo `file_replacement(PENDING)`, presigned POST (TTL `UPLOAD_TTL_MINUTES`, ràng buộc như §6.3). 201 `{submissionId,status,replacement:{replacementId,version,upload:{url,fields},uploadExpiresAt}}`. **Trạng thái submission chưa đổi.** |
| `POST /api/v1/submissions/{id}/replace-file/{replacementId}/complete` | Body `{}`. HEAD object; kiểm lại điều kiện. Rồi, theo thứ tự: (1) gọi `discard-open-work` (idempotent) (2) **một transaction**: ghi `ExamFile` ORIGINAL `v n` (`is_current=true`, `sha256` null), đặt ExamFile cũ (ORIGINAL + ANONYMIZED) `is_current=false`, `Submission.current_file_version=n`, đóng Review `ACTION_AUTHORIZED(REPLACE_FILE)` thành `RESOLVED(REPLACE_FILE)`, `file_replacement=COMPLETED`, T24/T25 → `UPLOADED`, tạo `ProcessingJob(VALIDATE, file_version=n)` (3) publish `validate`. 200 `{submissionId,status:"UPLOADED",version:n}`. Gọi lại khi đã `COMPLETED` → 200 idempotent. Lỗi: 409 `REPLACEMENT_EXPIRED`, 409 `UPLOAD_NOT_FOUND`, 422 `FILE_TOO_LARGE` (xóa object, `file_replacement=CANCELLED`, submission giữ trạng thái cũ). Nếu bước (1) lỗi 503 thì trả 503, client gọi lại. |
| `DELETE /api/v1/submissions/{id}/replace-file` | Hủy yêu cầu `PENDING` (xóa object nếu đã lên); 204. Không có yêu cầu → 404 `REPLACEMENT_NOT_FOUND`. |
| `GET /api/v1/submissions/{id}/files` | EO. Danh sách `{type,version,isCurrent,sizeBytes,sha256?,createdAt}`; không trả object key hay URL (muốn xem dùng `files/{type}/access?version=`). |

**Hệ quả sau `complete`:**
- Xử lý lại từ VALIDATE (T05…): đọc trang/kích thước (±5%), tính SHA-256 `v n`, **giữ nguyên phách** (gọi Identity `POST /internal/v1/pseudonyms` cùng `idempotencyKey=submissionId:pseudonym` trả về phách hiện hành, D-10), rồi ANONYMIZE tạo `anonymized/{examId}/{submissionId}/v{n}.pdf`. Thành công → `READY_FOR_GRADING` (T09), lỗi → như bài mới (T07/T08/T10/T11).
- Bản ORIGINAL cũ **không bị sửa hay xóa**, còn đến hết retention; không còn là bản hiện hành, chỉ EO xem được qua `?version=`. GRADER chỉ nhận ANONYMIZED hiện hành.
- Assignment mở bị hủy, lượt `DRAFT` thành `DISCARDED` (không xóa, phục vụ truy vết); EO giao lại khi bài `READY_FOR_GRADING`.
- Nếu xử lý lại thất bại và bài vào `REVIEW_REQUIRED` do `ANONYMIZATION` mới (T08/T11) hay `FAILED`, EO có thể thay file lần nữa.
- Audit: ghi `REPLACE_FILE_REQUESTED/COMPLETED` với `reason`, `version`, `actor_id` (không ghi object key hay URL).

**Dọn dẹp:** scheduler chuyển `file_replacement` `PENDING` quá `upload_expires_at` thành `EXPIRED` (submission không đổi) và xóa object mồ côi theo quy tắc: object không có `ExamFile` **và** không thuộc `file_replacement PENDING` chưa hết hạn.

**Bảng mới** (Submission DB): `file_replacement(replacement_id uuid PK, submission_id uuid FK, version int NOT NULL, object_key varchar(512) NOT NULL, declared_size_bytes bigint NOT NULL CHECK (declared_size_bytes <= 52428800), reason text NOT NULL, status varchar(20) NOT NULL CHECK (status IN ('PENDING','COMPLETED','EXPIRED','CANCELLED')), upload_expires_at timestamptz NOT NULL, requested_by uuid NOT NULL, created_at timestamptz NOT NULL, completed_at timestamptz, UNIQUE(submission_id, version))` cùng partial unique `(submission_id) WHERE status='PENDING'`.

### 4.5 Xem trước vùng che (D-23)

Giúp EXAM_OFFICE kiểm tra vùng che có **che thiếu** thông tin nhận dạng không (tên, SBD, lớp…), trước khi cho bài chạy tiếp (đặc biệt trước khi thực thi `FORCE_ANONYMIZE` ở T17) hoặc khi chỉnh tọa độ template.

`POST /api/v1/submissions/{id}/anonymization-preview` (**chỉ EO**; GRADER/COUNCIL → 403)

| Trường body | Mô tả |
|---|---|
| `version?` | Phiên bản ORIGINAL (mặc định bản hiện hành). |
| `templateId?` | Mặc định template của submission. |
| `override?` | `{mask:{x,y,width,height}, print?:{x,y,width,height,fontName,fontSize,align}}` thử tọa độ mới **không lưu**, kiểm theo §6.4 (ngoài [0,1] → 422 `INVALID_REGION`). |
| `mode?` | `MASKED` (mặc định): đúng hình trang 1 mà giám khảo sẽ thấy sau ẩn danh. `OUTLINE`: trang 1 **gốc** kèm khung đỏ (vùng che) và khung xanh (vùng in phách), cho thấy chỗ nào chưa được che. |
| `dpi?` | 50 đến 200, mặc định 100. `dpi=ANON_RENDER_DPI` cho hình khớp từng pixel với bản ANONYMIZED. |

Phản hồi 200 `image/png` (chỉ trang 1), `Cache-Control: no-store`, `Content-Disposition: inline`. Header phụ: `X-Page-Width-Pt`, `X-Page-Height-Pt`, `X-Template-Deviation-Percent` (lớn nhất trên mọi trang), `X-Size-Check: PASS|FAIL`.

**Quy tắc**
- **Không lưu gì**: không ghi object, không ghi `ExamFile`, không đổi trạng thái hay Review. Chỉ ghi `audit_log` `ANONYMIZATION_PREVIEWED` (`submissionId`, `version`, `mode`; không có ảnh).
- Dùng **chung một hàm** `PageMasker` với worker ANONYMIZE (§7) để preview và kết quả thật không lệch nhau.
- `OUTLINE` chứa danh tính thật nên chỉ EO; hình trả về không lưu cache, không ghi log.
- Cho phép với bài đã có ORIGINAL (mọi trạng thái trừ `UPLOADING`). Đã purge → 409 `PURGED_DATA_UNAVAILABLE`; chưa có ORIGINAL → 409 `INVALID_STATE`; PDF hỏng/vượt giới hạn §7.5 → 422 `PDF_CORRUPT`.
- Tài nguyên: chạy đồng bộ trong Submission Service, tải PDF về file tạm (xóa ngay sau khi xong, không nạp 50 MB vào heap), semaphore `PREVIEW_MAX_CONCURRENCY=2`, timeout `PREVIEW_TIMEOUT_SECONDS=15`, giới hạn 10 lần/phút/người. Hết chỗ → 429 `PREVIEW_BUSY`.
- Hiệu chỉnh template **trước khi kỳ thi `ACTIVE`** không thể dùng endpoint này (chưa có submission, Q-08) → dùng bản xem trước bằng bài mẫu bên dưới.

#### Xem trước bằng bài mẫu (chưa có submission)

`POST /api/v1/paper-templates/preview` (**chỉ EO**), `multipart/form-data`:

| Trường | Mô tả |
|---|---|
| `file` | PDF mẫu, ≤ `PREVIEW_MAX_BYTES` (mặc định 20 MB, 413 nếu vượt). Chỉ trang 1 được dựng; kích thước mọi trang vẫn được đọc để tính độ lệch. Nên dùng **tờ giấy thi trắng hoặc bài mẫu đã xóa danh tính**. |
| `templateId?` | Dùng tọa độ của template đã có. |
| `region?` | `{mask:{x,y,width,height}, print?:{…}}` tọa độ thử (cùng quy tắc §6.4). Bắt buộc có một trong `templateId`/`region`; có cả hai thì `region` thắng. |
| `mode?`, `dpi?` | Như trên. |

Phản hồi, header, `PageMasker`, giới hạn tài nguyên (`PREVIEW_MAX_CONCURRENCY`, `PREVIEW_TIMEOUT_SECONDS`, 10 lần/phút/người) giống `anonymization-preview`. `X-Template-Deviation-Percent` và `X-Size-Check` chỉ có khi truyền `templateId`. **Không lưu** bài mẫu: ghi vào file tạm, xóa ngay kể cả khi lỗi; không log nội dung; `Cache-Control: no-store`; `audit_log` ghi `TEMPLATE_PREVIEWED` (không có ảnh, không có tên file). Mục đích: chốt tọa độ trong lúc Exam còn `DRAFT`, rồi mới tạo template bất biến (Q-09) và kích hoạt kỳ thi.

### 4.6 Mở lại vấn đề ẩn danh khi bài đã `READY_FOR_GRADING` (D-24)

Dùng khi EO xem trước (§4.5) thấy vùng che **thiếu** ở một bài đã ẩn danh xong nhưng chưa giao chấm.

**Luồng**
1. EO gọi `POST /submissions/{id}/anonymization-issues {reason}` → T27: Review `ANONYMIZATION`/`MASK_INSUFFICIENT` `OPEN`, bài `REVIEW_REQUIRED`. Bài rời `READY_FOR_GRADING` nên **không còn được giao chấm** (bulk assignment đưa vào `rejected[]`, D-11).
2. COUNCIL xem Review (ghi chú của EO, `anonymizedFileAccessUrl`; không thấy ORIGINAL) và quyết định (§3.2):
   - `DISMISS`: chấp nhận bản ẩn danh hiện có → T28, bài về `READY_FOR_GRADING`.
   - `REPROCESS`: dựng lại bản ẩn danh. EO gọi `retry {step:"ANONYMIZE", reviewId, templateId?}` (T18). Nếu nguyên nhân là tọa độ template sai, EO dùng `templateId` của template đã chỉnh (xem trước bằng `paper-templates/preview`).
   - `REPLACE_FILE`: quét lại bài (T24, §4.4; chặn bởi `ATTEMPTS_EXIST` nếu đã có lượt `CONFIRMED`, thực tế không xảy ra vì bài chưa từng được giao).
3. Kết thúc: bài về `READY_FOR_GRADING` sau khi dựng lại thành công (T09). Hết retry → `FAILED/ANONYMIZE_RETRY_EXHAUSTED` (xử lý như T19).

**Dựng lại bản ẩn danh đã có (`overwrite=true`)**
- Worker dựng bản mới ra file tạm, rồi ghi đè object ANONYMIZED **cùng key** `anonymized/{examId}/{submissionId}/v{n}.pdf` (ghi đè là nguyên tử ở S3), cập nhật dòng `ExamFile` ANONYMIZED (`size_bytes`, `sha256`) trong cùng transaction với T09. Không tạo phiên bản file mới vì ORIGINAL không đổi; ghi đè hợp lệ vì **chưa có assignment** nào đọc bản này.
- Khi bài ở `VALIDATING`/`ANONYMIZING`, `files/ANONYMIZED/access` trả 409 `FILE_NOT_READY` (không cấp URL của bản cũ đang bị thay).
- Template mới (`templateId`): worker kiểm lại ±5% mọi trang với template đó. Lệch → T11 (`TEMPLATE_MASK_ERROR`, `detail.deviationPercent`), `Submission.template_id` **giữ nguyên**, Review mới được tạo (Review cũ đã đóng ở T18). Thành công → `Submission.template_id` = template mới, `audit_log` `TEMPLATE_CHANGED` `{from,to}`. `Exam.template_id` không đổi, các bài khác không bị ảnh hưởng.

**Giới hạn đã chấp nhận**: sau khi bài đã giao chấm (`GRADING` trở đi), giám khảo có thể đã nhìn thấy phần che thiếu; khi đó chỉ có đường `GRADER_FLAG` (Hội đồng `REPLACE_FILE`/`REGRADE`/`DISMISS`), không có `REPROCESS`, và EO không mở Review ẩn danh thủ công được.

## 5. Bảng hạ tầng bổ sung (mọi service)

```sql
audit_log(id uuid pk, actor_id varchar(100), actor_role varchar(30), action varchar(80),
          resource_type varchar(50), resource_id uuid, request_id uuid, at timestamptz not null default now(),
          detail jsonb)           -- không PII, không phách, không điểm câu
idempotency_record(idempotency_key varchar(200), operation varchar(80), request_hash char(64),
          response_status int, response_body jsonb, created_at timestamptz,
          primary key(idempotency_key, operation))
```
Replay cùng key + cùng `request_hash` → trả lại response đã lưu. Cùng key nhưng khác hash → 409 `IDEMPOTENCY_KEY_REUSED`.

Bổ sung cột/bảng:
- **Review** (Result DB): `status` ∈ `OPEN|ACTION_AUTHORIZED|RESOLVED`; `technical_code varchar(40)` (`TEMPLATE_SIZE_MISMATCH|TEMPLATE_MASK_ERROR|MASK_INSUFFICIENT`, chỉ với `ANONYMIZATION`); `detail jsonb`; `authorized_action varchar(20)` (`FORCE_ANONYMIZE|REPROCESS|REPLACE_FILE|REGRADE`); `resolution_type` ∈ `DISMISS|SET_FINAL_SCORE|FORCE_ANONYMIZE|REPROCESS|REPLACE_FILE|REGRADE|WITHDRAWN`; `previous_status`; `regrade_attempt_id`; `created_by uuid` (dùng UUID hệ thống cố định cho Review do worker/Result tạo); `version int` (khóa lạc quan, D-20). **Bỏ `handler_role`.** Index duy nhất bảo đảm D-21: `CREATE UNIQUE INDEX uq_review_one_unclosed ON review(submission_id) WHERE status IN ('OPEN','ACTION_AUTHORIZED')`.
- **CouncilDecision**: `decision_id`, `review_id` FK, `decision_type` (cùng enum trừ `WITHDRAWN`), `final_score numeric(7,2)` nullable (`CHECK (decision_type <> 'SET_FINAL_SCORE' OR final_score IS NOT NULL)`), `decision_source` (`COUNCIL|REMARK`, chỉ với `SET_FINAL_SCORE`), `reason text NOT NULL`, `decided_by uuid`, `decided_at`.
- **review_note**: `note_id uuid PK, review_id FK, author_id uuid, author_label varchar(10), text text NOT NULL, created_at`.
- `GradingAttempt.status` thêm `DISCARDED`; `GradingAssignment.review_id` (nullable); `Rubric.status` thêm `SUPERSEDED`; bảng `exam_grader(exam_id uuid, user_id uuid, added_by uuid, added_at timestamptz, PRIMARY KEY(exam_id,user_id))` thay `grader_registry`.
- **ExamFile** thêm `version int NOT NULL DEFAULT 1`, `is_current boolean NOT NULL DEFAULT true`, `UNIQUE(submission_id,type,version)` và partial unique `(submission_id,type) WHERE is_current` (thay `UNIQUE(submission_id,type)`); `Submission.current_file_version int NOT NULL DEFAULT 1`; `Submission.binding_released boolean NOT NULL DEFAULT false`; `ProcessingJob.file_version int NOT NULL DEFAULT 1`, `ProcessingJob.force boolean NOT NULL DEFAULT false`, `ProcessingJob.overwrite boolean NOT NULL DEFAULT false`, `ProcessingJob.template_id uuid` (template dùng cho lần chạy; mặc định `Submission.template_id`); bảng `file_replacement` (§4.4).
- **FinalResult** thêm `version int NOT NULL DEFAULT 1`, `is_current boolean NOT NULL DEFAULT true`, `retention_hold boolean NOT NULL DEFAULT false`, `superseded_at timestamptz`, `UNIQUE(submission_id,version) WHERE submission_id IS NOT NULL`, partial unique `(submission_id) WHERE is_current` (E-13). `retention_deadline` của phiên bản ≥ 2 sao chép từ phiên bản 1 (D-15).
- Mọi cột định danh người dùng ở các service là `uuid` (E-12). Purge xóa `audit_log` có `resource_id` thuộc submission đã hết hạn.

### 5.2 Binding thí sinh – bài nộp (Identity DB, E-14)

```sql
CREATE TABLE submission_binding (
  binding_id      uuid PRIMARY KEY,
  exam_id         uuid NOT NULL,
  submission_id   uuid NOT NULL UNIQUE,
  candidate_id    uuid NOT NULL REFERENCES candidate(candidate_id),
  status          varchar(10) NOT NULL CHECK (status IN ('ACTIVE','RELEASED')),
  released_reason varchar(40),
  created_at      timestamptz NOT NULL,
  released_at     timestamptz,
  CHECK ((status = 'RELEASED') = (released_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_binding_one_active_per_candidate
  ON submission_binding(candidate_id) WHERE status = 'ACTIVE';
```
- **"Submission đang sống" = có binding `ACTIVE`.** Binding `ACTIVE` từ T01 và **được giữ** ở mọi trạng thái, kể cả `FAILED` có thể phục hồi (`PDF_CORRUPT`, `PAGE_COUNT_INVALID`, `*_RETRY_EXHAUSTED`) và `FINALIZED`. Thí sinh có bài `FAILED` phục hồi được phải xử lý bằng thay file/retry, không tạo submission mới.
- **Nhả binding** (`RELEASED`) chỉ khi submission không thể phục hồi: `FAILED/UPLOAD_ABANDONED` (T03) và `FAILED/FILE_TOO_LARGE` (T04). Gửi bằng outbox (§12) tới `POST /internal/v1/submission-bindings/{submissionId}/release`; khi Identity xác nhận thì Submission đặt `binding_released=true`. Scheduler dọn upload dang dở (ABANDONED_SCAN_CRON) **đối soát**: gửi lại release cho mọi submission `FAILED` thuộc hai mã trên mà `binding_released=false`.
- Unique index một-`ACTIVE`-mỗi-candidate xử lý race khi hai request init đồng thời: một thành công, một nhận 409 `CANDIDATE_ALREADY_BOUND`.
- `Pseudonym.candidate_id` lấy từ binding khi sinh phách (`POST /internal/v1/pseudonyms` chỉ nhận `{examId,submissionId,idempotencyKey}`). Binding `RELEASED` → không sinh phách (409 `BINDING_RELEASED`).
- **Purge**: lệnh `DELETE /internal/v1/identity/submissions/{id}/pseudonym` xóa cả dòng `submission_binding` (đây là ánh xạ thí sinh–bài nộp, thuộc dữ liệu chi tiết, FR-025/BR-006).


## 6. Bảo mật và định dạng

### 6.1 JWT
- Claim `sub` = `users.user_id` (UUID); claim `role` (đổi được bằng `JWT_ROLE_CLAIM`) = đúng một trong `EXAM_OFFICE|GRADER|COUNCIL`. Nhiều role → 403.
- Verify bằng JWKS hoặc public key (`JWT_ISSUER`, `JWT_PUBLIC_KEY`/`JWT_JWKS_URI`), kiểm `exp`, `iss`.

### 6.2 Chữ ký nội bộ
- User context: `X-User-Id, X-Role, X-Request-Id, X-Timestamp(epoch ms), X-Identity-Signature = hex(HMAC-SHA256(secret, userId+"\n"+role+"\n"+requestId+"\n"+timestamp))`. Cửa sổ ±60 giây; request-id đã thấy thì từ chối (cache 2 phút).
- Service-to-service thêm `X-Service-Name, X-Service-Signature = hex(HMAC-SHA256(secretOf(service), serviceName+"\n"+requestId+"\n"+timestamp))`. Mỗi endpoint `/internal/**` có **allowlist caller** (ví dụ `/internal/v1/grading/attempts-confirmed` chỉ `exam-grading`).

### 6.3 Presigned qua Gateway và allowlist `/storage` **[cần spike S0 trước khi làm upload]**
- MinIO SDK cấu hình với endpoint nội bộ `http://minio:9000`; sau khi sinh URL, thay scheme+host bằng `{PUBLIC_BASE}/storage`.
- Gateway: `Path=/storage/**`, `StripPrefix=1`, **đặt `Host: minio:9000`** khi forward (chữ ký SigV4 có ký header Host), không buffer request.
- Presigned POST: điều kiện `Content-Type` = `application/pdf`, `content-length-range` = `[1, declaredSize + 1024]`, `key` cố định, `bucket` cố định.
- **Allowlist tại Gateway** (mọi thứ không khớp bị chặn **trước khi** tới MinIO, không dựa vào việc MinIO từ chối):
  | Yêu cầu | Quy tắc | Không khớp |
  |---|---|---|
  | Đường dẫn | Khớp `^/storage/(original\|anonymized)(/.*)?$` | 404 |
  | Upload | Chỉ `POST /storage/original` (đúng đường dẫn bucket, không có object key, không query string), `Content-Type: multipart/form-data`, body ≤ `MAX_PDF_BYTES + 65536` | 405 / 413 |
  | Tải/xem | Chỉ `GET`/`HEAD` tới `/storage/{original\|anonymized}/{examId}/{submissionId}/v{n}.pdf` (hai UUID hợp lệ, `n` là số), bắt buộc có `X-Amz-Signature` | 405 / 403 |
  | Query khi tải | Chỉ cho `X-Amz-Algorithm, X-Amz-Credential, X-Amz-Date, X-Amz-Expires, X-Amz-SignedHeaders, X-Amz-Signature, response-content-type, response-content-disposition`. Các tham số khác (`list-type`, `uploads`, `acl`, `delete`, `versions`, `tagging`…) | 403 |
  | Phương thức khác | `PUT`, `DELETE`, `OPTIONS`, `GET /storage/original` (liệt kê bucket), `GET /storage/` | 405 |
  | Đường dẫn MinIO khác | `/minio/**` (admin, health, metrics), console (cổng 9001 không publish) | 404 |
- **Header**: xóa `Authorization`, `Cookie`, `X-User-*`, `X-Service-*`, `X-Identity-Signature`, `X-Forwarded-*` do client gửi (tránh MinIO thấy hai kiểu xác thực và tránh lộ JWT); cho qua `Range`, `If-Range`, `Content-Type`, `Content-Length` (PDF.js cần `Range`). Response thêm `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`; xóa `Server` và `X-Minio-*`.
- Rate limit theo IP `STORAGE_RATE_LIMIT_PER_MINUTE` (mặc định 120), pool kết nối/timeout tách khỏi route API (NFR); **không log query string** của `/storage` (chứa chữ ký).
- Spike S0 phải chứng minh bằng test tự động: upload 50 MB qua Gateway thành công; sai key/vượt size/hết hạn bị MinIO từ chối; presigned GET qua Gateway tải được kể cả `Range`; và **toàn bộ bảng allowlist ở trên** (các test AT-N01…AT-N12).

### 6.4 Schema JSON
`PaperTemplate.pseudonym_print_region` và mask:
```json
{ "mask": {"x":0.60,"y":0.02,"width":0.35,"height":0.10},
  "print": {"x":0.62,"y":0.04,"width":0.31,"height":0.06,"fontName":"Helvetica-Bold","fontSize":18,"align":"CENTER"} }
```
Tọa độ tỉ lệ [0,1] so với trang 1, **gốc ở góc trên-trái**, `x+width ≤ 1`, `y+height ≤ 1`. PDFBox dùng gốc dưới-trái nên đổi `y_pdf = H − (y+height)·H`. `page_width/page_height` tính bằng PDF point (A4 = 595×842). Dung sai ±5% kiểm trên **mọi trang**, so sánh `MediaBox` đã áp `/Rotate`, không hoán đổi chiều rộng/cao. Bất kỳ trang nào lệch → REVIEW_REQUIRED (T08).

`Rubric.content_json` (nhập tay):
```json
{ "type":"MANUAL", "items":[ {"questionOrder":1,"text":"…","criteria":[{"label":"…","maxScore":2.0,"text":"…"}]} ] }
```
`Rubric.content_json` (DOCX): `{ "type":"DOCX_TABLE", "rows":[{"cells":[{"text":"…","rowspan":1,"colspan":1}]}] }` giữ nguyên 1-1 hàng/cột/ô gộp; nested table → 422 `UNSUPPORTED_DOCX_TABLE`. Regex gợi ý điểm chỉ prefill, không bao giờ tự kích hoạt.

## 7. Ẩn danh phải thật sự loại bỏ dữ liệu (thay E-07)

Bản ANONYMIZED **không được** chỉ vẽ hình chữ nhật đè lên trang gốc. Cài đặt bắt buộc:
1. Trang 1: render sang **bitmap RGB 8-bit** (mặc định 200 DPI, cấu hình được; nếu vượt `PDF_MAX_RENDER_PIXELS` thì tự giảm DPI nhưng không dưới 100) → tô kín vùng mask trên bitmap bằng **trắng `#FFFFFF`** → vẽ phách (chữ đen) trong vùng `print` (nằm trong vùng mask) → tạo trang mới từ bitmap bằng `LosslessFactory.createFromImage` (nén Flate). **Cấm dùng JPEG/JPX hoặc nén mất dữ liệu cho trang 1** (làm nhòe biên vùng che và hỏng bài kiểm tra bên dưới). Không giữ ảnh gốc, text layer, annotation.
2. Các trang còn lại có thể copy nguyên; nếu trang đó chứa annotation/form field/attachment thì loại bỏ.
3. Xóa toàn bộ metadata (Info dictionary, XMP), không lưu tên file gốc trong PDF.
4. Test bắt buộc: giải mã ảnh nhúng ở trang 1 của PDF ANONYMIZED và xác nhận **mọi pixel trong vùng mask, ngoài vùng `print` (chừa lề 2 px), bằng đúng `#FFFFFF` (sai số 0)**; trang 1 chỉ có một image XObject, văn bản chỉ là phách, không có annotation/XObject nào khác. Hàm dựng trang 1 là `PageMasker` dùng chung với `anonymization-preview` (§4.5).
5. **Giới hạn tài nguyên khi xử lý PDF không tin cậy** (worker chạy không đặc quyền, container có giới hạn bộ nhớ): `PDF_MAX_PAGE_POINTS=14400` (mỗi cạnh MediaBox, ≈200 inch), `PDF_MAX_RENDER_PIXELS=40000000`, `PDF_JOB_TIMEOUT_SECONDS=120` (hủy job, không để treo consumer). Vượt giới hạn → `FAILED`, `failure_code=PDF_CORRUPT`, `error_detail=LIMIT_EXCEEDED`, không retry. Không thực thi JavaScript/embedded file/launch action trong PDF.
6. Bản ORIGINAL không bị sửa; so SHA-256 trước/sau xử lý phải bằng nhau (BR-013).

## 8. Topology RabbitMQ (D-18: VALIDATE + ANONYMIZE)

**Luồng**: `/complete` (T02) → publish `validate` → worker VALIDATE (T05–T08, sinh phách qua Identity, publish `anonymize`) → worker ANONYMIZE (T06 status, T09–T11).

- Exchange `submission.processing` (direct, durable). Hai routing key / queue chính, tách consumer pool và concurrency:
  | routing key | queue | Worker | Concurrency mặc định |
  |---|---|---|---|
  | `validate` | `submission.validate.v1` | VALIDATE (tải PDF, đọc trang/kích thước, SHA-256) | 2 (cấu hình `VALIDATE_CONCURRENCY`) |
  | `anonymize` | `submission.anonymize.v1` | ANONYMIZE (rasterize + che, §7) | 2 (`ANONYMIZE_CONCURRENCY`) |
- Retry (mỗi routing key có bộ riêng): `submission.{validate|anonymize}.retry.30s|2m|10m` (queue chờ, `x-message-ttl` 30000 / 120000 / 600000, `x-dead-letter-exchange=submission.processing`, `x-dead-letter-routing-key=<key>`); dead letter cuối `submission.{validate|anonymize}.dlq`.
- Header `x-attempt` (bắt đầu 0). Lỗi transient → `attempt+1` publish vào retry queue tương ứng; khi `attempt > 3` → ghi `ProcessingJob=FAILED`, chuyển `Submission=FAILED` (`failure_code`: `VALIDATE_RETRY_EXHAUSTED` hoặc `ANONYMIZE_RETRY_EXHAUSTED`), **ack**.
- Phân loại lỗi VALIDATE: PDF hỏng/không đọc được → FAILED `PDF_CORRUPT` (không retry, ack); số trang ngoài 1–20 → FAILED `PAGE_COUNT_INVALID`; lệch template > 5% → REVIEW_REQUIRED (không retry); S3/Identity/DB tạm thời lỗi → retry.
- Message `{schemaVersion:1,jobId,submissionId,examId,templateId,fileVersion,force?,pseudonym?,attempt,correlationId}`; `fileVersion` bắt buộc (bài mới = 1). **Chống message cũ**: worker bỏ qua (ack, không đổi gì) message có `fileVersion` ≠ `Submission.current_file_version`. `force=true` chỉ có ở job `validate` sau `FORCE_ANONYMIZE` (T17) và bỏ qua kiểm tra lệch kích thước ±5%. `overwrite=true` (chỉ job `anonymize` sau `REPROCESS` của `MASK_INSUFFICIENT`) và `templateId` được worker đọc từ `ProcessingJob` theo `jobId`, không tin giá trị trong message. Message `validate` **không** chứa pseudonym; cả hai **không** chứa candidateId/họ tên. Consumer **validate schema** (thiếu field/`schemaVersion` lạ) → đưa thẳng vào `*.dlq`, không retry, tăng metric `mq_invalid_messages_total`.
- Manual ack **sau commit**; publisher confirms bật (kiểm tra `ack` từ broker trước khi coi job là đã gửi).
- Độ tin cậy publish: ghi `ProcessingJob(status=QUEUED)` trong cùng transaction với đổi status (T02 / sau T06). Reconciler mỗi 5 phút requeue `ProcessingJob` ở `QUEUED` quá 10 phút (cả hai step). Nếu muốn chặt hơn có thể dùng transactional outbox (không bắt buộc cho MVP).
- Idempotency: worker kiểm `ProcessingJob.status`, `Submission.status` trước khi chạy và **bỏ qua nếu chính `jobId` này đã `SUCCEEDED`**; sự tồn tại của object ANONYMIZED chỉ được dùng để bỏ qua khi job **không** có `overwrite` (job `overwrite=true` luôn dựng lại và ghi đè, nếu không bản che thiếu sẽ không bao giờ được thay); bước VALIDATE tạo pseudonym bằng `idempotencyKey=submissionId:pseudonym`; chỉ publish `anonymize` nếu job chưa `SUCCEEDED`.
- Thứ tự an toàn: worker VALIDATE chỉ ack sau khi đã ghi DB **và** publish `anonymize` được broker xác nhận; nếu publish thất bại thì để retry (idempotent nhờ các kiểm tra trên).

## 9. Quy tắc nghiệp vụ cần triển khai chính xác

### 9.1 Đánh giá khi một lượt chấm được confirm (Result/Review)
1. Lấy snapshot (`/internal/v1/submissions/{id}/grading-snapshot`).
2. Nếu submission có **Review chưa đóng** (`OPEN`/`ACTION_AUTHORIZED`) → dừng (Q-05, D-21). Lượt `is_regrade=true` không tham gia so sánh.
3. Số lượt CONFIRMED < số cần (cross: `graderCount`; không cross: 1) → chờ.
4. Không cross: T13. Có cross: so **mọi cặp** `|ti − tj| ≤ threshold` trên **tổng điểm chính xác** (BigDecimal, chưa làm tròn). Tất cả đạt → T13; có cặp vượt → tạo Review `SCORE_DIFFERENCE` rồi T14.
5. Điểm cuối dự kiến = `mean(all totals)` làm tròn `HALF_UP` scale 2, chỉ làm tròn ở bước cuối, chốt khi finalize.

### 9.2 Finalize (saga tại Result/Review, idempotent)
Kiểm Submission đang `COMPLETED` → không có Review chưa đóng → đủ lượt chấm → xác định nguồn điểm: nếu tồn tại `CouncilDecision` `SET_FINAL_SCORE` thì lấy cái có `decided_at` mới nhất (`decision_source` = `COUNCIL`, hoặc `REMARK` nếu từ Review `REMARK`); nếu không có thì trung bình chéo (`CROSS_GRADING`); nếu không chấm chéo thì lượt duy nhất (`SINGLE_GRADER`) → gọi Identity lấy danh tính + Exam&Grading lấy exam/môn → lưu `FinalResult` → gọi Submission T20 (với `finalizedAt` chỉ khi là lần đầu).
- Lần đầu: `version=1`, `is_current=true`.
- Sau phúc khảo (đã có phiên bản trước): **một transaction** đặt phiên bản cũ `is_current=false, superseded_at=now`, chèn phiên bản `n+1` (`is_current=true`, `retention_hold=false`, `finalized_at=now` của phiên bản, **`retention_deadline` sao chép nguyên từ phiên bản 1**, `purge_status=PENDING`). `Submission.finalized_at` **không đổi** (D-15).
- Gọi lại `finalize` khi Submission đã `FINALIZED` và không có chu trình mới → 200, cùng `resultId` (E-10). Lỗi giữa chừng (đã lưu FinalResult, chưa đổi status) → gọi lại không tạo phiên bản thứ hai (idempotency theo `submissionId + version`).

### 9.3 Retention
Scheduler hàng ngày (cron cấu hình, mặc định 02:00 UTC): chọn `FinalResult` có `is_current=true`, `retention_hold=false`, `retention_deadline ≤ now`, `purge_status ∈ {PENDING,FAILED}`. Chuyển sang `IN_PROGRESS` bằng **cập nhật có điều kiện** (`UPDATE … WHERE purge_status IN ('PENDING','FAILED') AND retention_hold=false`; 0 dòng ⇒ bỏ qua, tránh race với phúc khảo). Gọi 3 lệnh purge (Header `Idempotency-Key=resultId:retention:v1`) + tự xóa Review/CouncilDecision/audit liên quan; đủ 4 `PurgeReceipt=DONE` mới đặt `PURGED` và `FinalResult.submission_id=NULL`. Gọi lại khi đã xóa trả 200. Lệnh purge của Submission xóa **mọi phiên bản** ExamFile (ORIGINAL `v1..vn` và ANONYMIZED), không chỉ bản hiện hành. Phiên bản `is_current=false` không bị purge (chỉ chứa dữ liệu lõi, D-15). `retention_deadline` chỉ được đặt một lần ở lần FINALIZED đầu tiên; phúc khảo **không gia hạn**. Nếu phúc khảo kéo dài quá hạn, `retention_hold` chỉ hoãn purge trong lúc Review chưa đóng; ngay sau khi gỡ hold, lần chạy scheduler kế tiếp sẽ purge. Metric `retention_hold_age_days` cảnh báo phúc khảo mở quá lâu.

### 9.5 Phúc khảo, gồm cả sau FINALIZED (D-12, D-13, D-15)
1. **Ai**: EXAM_OFFICE tạo yêu cầu (`POST /submissions/{id}/remark`, có `reason`; việc xác nhận thí sinh thuộc giao diện). **Hội đồng quyết định** (§3.2).
2. **Cửa sổ hợp lệ**: submission `COMPLETED` hoặc `FINALIZED`. Với `FINALIZED`: `FinalResult` hiện hành có `purge_status=PENDING`, `now < retention_deadline`, và nếu `REMARK_WINDOW_DAYS>0` thì `now < first_finalized_at + N ngày` (Q-14). Kiểm tra và đặt `retention_hold=true` bằng **một lệnh cập nhật có điều kiện** (`WHERE purge_status='PENDING' AND retention_deadline > now() AND retention_hold=false`); 0 dòng ⇒ 409 `REMARK_WINDOW_CLOSED`, và nếu `purge_status ∈ {IN_PROGRESS, PURGED, FAILED}` ⇒ 409 `PURGED_DATA_UNAVAILABLE`.
3. **Tạo**: Review `REMARK` (`OPEN`, `previous_status`), T21/T22 → `REVIEW_REQUIRED`. Hội đồng chọn:
   - `DISMISS` (bác) → T23, trả về trạng thái cũ, gỡ hold.
   - `SET_FINAL_SCORE` (quyết ngay trên các lượt chấm hiện có) → T16 `COMPLETED`.
   - `REGRADE` → `ACTION_AUTHORIZED`; EO chọn giám khảo và tạo SINGLE_REVIEW (`reviewId`), T15b. Lượt mới `attemptNo` tăng, `is_regrade=true`; giám khảo **không thấy** điểm/ghi chú cũ; lượt cũ giữ nguyên đến hết retention (FR-024). Lượt chấm lại **không** tham gia so sánh cặp §9.1. Khi lượt này `CONFIRMED` → T26, Review về `OPEN`; Hội đồng xem tất cả lượt (nhãn GK/CL) và `SET_FINAL_SCORE` → T16.
4. **Chốt lại**: EO gọi `finalize` → `FinalResult` phiên bản mới (§9.2). **`retention_deadline` và `Submission.finalized_at` không đổi.** Hold được gỡ ở bước này.
5. **Rút**: EO `withdraw` (T23) trước khi Hội đồng `SET_FINAL_SCORE`; hủy assignment/lượt chấm lại chưa CONFIRMED (lượt đã CONFIRMED vẫn giữ làm lịch sử).
6. Export (FR-021) và `GET /results` chỉ dùng phiên bản `is_current=true`; bài đang phúc khảo xuất kèm trạng thái `REVIEW_REQUIRED` và điểm của phiên bản hiện hành.

### 9.4 Export (FR-021)
Cột: Kỳ thi, Môn, SBD, Họ tên, Lớp, Phách, rồi với mỗi GKi: `GKi_Tổng, GKi_Câu1…CâuN, GKi_GhiChú`, sau đó Điểm cuối, Nguồn quyết định, Trạng thái, Ghi chú vấn đề. CSV: UTF-8 **có BOM**, dấu phẩy, escape RFC 4180. XLSX: SXSSF streaming. `Cache-Control: no-store`. Sau purge: giữ các cột lõi, bỏ trống phách/điểm câu.

## 10. Biến môi trường tối thiểu

`JWT_ISSUER, JWT_JWKS_URI|JWT_PUBLIC_KEY, JWT_ROLE_CLAIM, IDENTITY_HMAC_SECRET, SERVICE_SECRET_<NAME>, DB_URL/DB_USER/DB_PASSWORD (mỗi service), RABBITMQ_URL, S3_INTERNAL_ENDPOINT, S3_PUBLIC_BASE, S3_ACCESS_KEY, S3_SECRET_KEY, UPLOAD_TTL_MINUTES=10, DOWNLOAD_TTL_MINUTES=5, MAX_PDF_BYTES=52428800, MAX_DOCX_BYTES=20971520, ANON_RENDER_DPI=200, RETRY_BACKOFFS=30s,2m,10m, ORPHAN_SCAN_CRON, ABANDONED_SCAN_CRON, RETENTION_CRON, REMARK_ALLOW_AFTER_FINALIZED=true, REMARK_WINDOW_DAYS=0, PREVIEW_MAX_CONCURRENCY=2, PREVIEW_TIMEOUT_SECONDS=15, STORAGE_RATE_LIMIT_PER_MINUTE=120, AUTH_LOCAL_ENABLED=true, JWT_SIGNING_KEY, JWT_TTL_MINUTES=15, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD, LOGIN_MAX_FAILURES=5, LOGIN_LOCK_MINUTES=15`. Không commit giá trị thật; `.env.example` bắt buộc.

## 11. Catalog mã lỗi bổ sung

`ILLEGAL_TRANSITION, STATUS_VERSION_CONFLICT, IDEMPOTENCY_KEY_REUSED, EXAM_NOT_ACTIVE, TEMPLATE_IN_USE, PDF_CORRUPT, PAGE_COUNT_INVALID, TEMPLATE_SIZE_MISMATCH, TEMPLATE_INACTIVE, FILE_NOT_READY, ANONYMIZE_RETRY_EXHAUSTED, GRADER_NOT_REGISTERED, ATTEMPT_ALREADY_CONFIRMED, REVIEW_ALREADY_OPEN, REVIEW_ALREADY_DECIDED, REVIEW_NOT_AUTHORIZED, DECISION_NOT_ALLOWED, REASON_REQUIRED, SCORE_OUT_OF_RANGE, BINDING_RELEASED, CANDIDATE_ALREADY_BOUND, INVALID_REGION, PREVIEW_BUSY, REMARK_WINDOW_CLOSED, USERNAME_TAKEN, EMAIL_TAKEN, LAST_EXAM_OFFICE, USER_NOT_FOUND, REPLACE_NOT_ALLOWED, ATTEMPTS_EXIST, REPLACEMENT_IN_PROGRESS, REPLACEMENT_NOT_FOUND, REPLACEMENT_EXPIRED, ROLE_CHANGE_FORBIDDEN, INVALID_CREDENTIALS, VALIDATE_RETRY_EXHAUSTED, RESULTS_NOT_FINALIZED, PURGED_DATA_UNAVAILABLE`. Ánh xạ HTTP: 409 cho trạng thái/xung đột, 422 cho vi phạm quy tắc nghiệp vụ, 403 `RESOURCE_FORBIDDEN` khi sai tài nguyên, 404 khi không tồn tại (không phân biệt "không tồn tại" và "không thuộc bạn" với GRADER để tránh dò).

## 12. Độ tin cậy khi gọi liên service (transactional outbox)

**Vấn đề**: nhiều bước đổi dữ liệu ở service A rồi phải báo service B. Nếu B tạm lỗi thì A đã commit mà B không biết (bài kẹt `GRADING`, có `REVIEW_REQUIRED` mà chưa có Review, đã có assignment nhưng status chưa `GRADING`…). Các luồng này **bắt buộc** dùng outbox, không gọi REST đồng bộ rồi bỏ qua lỗi.

**Bảng** (mỗi service có phát sinh gọi đi): 
```sql
outbox_event(event_id uuid pk, aggregate_id uuid not null, type varchar(80) not null,
  target_service varchar(30) not null, payload jsonb not null,
  idempotency_key varchar(200) not null unique,
  status varchar(10) not null default 'PENDING' check (status in ('PENDING','SENT','DEAD')),
  attempts int not null default 0, next_attempt_at timestamptz not null, last_error text,
  created_at timestamptz not null, sent_at timestamptz)
-- index (status, next_attempt_at)
```

**Quy tắc**
1. Thay đổi trạng thái của service A và dòng `outbox_event` được ghi **trong cùng một transaction**.
2. Relay (scheduler, `OUTBOX_RELAY_INTERVAL_MS=2000`) lấy `PENDING` đến hạn bằng `FOR UPDATE SKIP LOCKED`, gọi service đích kèm header `Idempotency-Key=<idempotency_key>`.
3. 2xx → `SENT`. 5xx/timeout → `attempts+1`, backoff 5s → 30s → 2m → 10m → 30m (trần 30m). Vượt `OUTBOX_MAX_ATTEMPTS=20` → `DEAD`, tăng metric `outbox_dead_total`, log `ERROR` (không PII). 4xx nghiệp vụ mà đích đã ở trạng thái mong muốn (vd `ILLEGAL_TRANSITION` nhưng status đã đúng) → coi là `SENT`; còn lại → `DEAD`.
4. **Thứ tự theo aggregate**: relay chỉ gửi event cũ nhất chưa `SENT` của mỗi `aggregate_id` (submissionId); event sau chờ event trước.
5. Phía nhận **bắt buộc idempotent** (`idempotency_record`, §5).
6. Event `DEAD` phát lại bằng `POST /internal/v1/outbox/{eventId}/replay` (chỉ service signature của chính service đó, không qua Gateway).

**Các luồng dùng outbox**

| Hành động | Ghi outbox ở | Đích | Cùng transaction với |
|---|---|---|---|
| Giám khảo confirm lượt chấm | Exam & Grading | Result `attempts-confirmed` | `Attempt=CONFIRMED` |
| Đánh giá chấm chéo → T13/T14; lượt chấm lại confirm → T26 | Result | Submission `/status` | Tạo Review (T14) / chuyển Review về `OPEN` (T26) |
| Finalize T20, phúc khảo T21/T22, rút T23 | Result | Submission `/status` | `FinalResult`/Review/`retention_hold` |
| Bulk assignment T12 | Exam & Grading | Submission `/status` (`READY_FOR_GRADING→GRADING`) | Tạo assignments |
| Worker T08/T11 (lỗi template) | Submission | Result `POST /internal/v1/reviews` | Đổi status `REVIEW_REQUIRED` |
| Hội đồng `decision` kết thúc Review → T15a/T16/T23 | Result | Submission `/status` | Ghi `CouncilDecision` và đóng Review |
| Hội đồng `decision` `REGRADE` | Result | (không gọi ai) | Review `ACTION_AUTHORIZED`; EO tự gọi `assignments` có `reviewId` → Exam & Grading kiểm Review qua Result |
| T03/T04 nhả binding | Submission | Identity `submission-bindings/{id}/release` | Đổi status `FAILED` |

Ngoại lệ có chủ đích: `replace-file/complete` (§4.4) gọi `discard-open-work` **đồng bộ** trước khi đổi dữ liệu, vì cần kết quả ngay và đã idempotent.

**Quét đối soát (lớp dự phòng)**: Result chạy `reconcile-grading` mỗi 15 phút: lấy qua `GET /internal/v1/submissions?status=GRADING&updatedBefore=<now-15m>` (Submission, phân trang) các bài rồi đánh giá lại theo §9.1 nếu đủ lượt `CONFIRMED` mà chưa có Review chưa đóng. Gọi lại đánh giá phải an toàn (idempotent). Metric `reconcile_fixed_total`.

**Hệ quả hiển thị**: giữa lúc ghi outbox và lúc relay thành công, trạng thái Submission có thể chậm vài giây so với dữ liệu ở service nguồn; SSE chỉ phát khi Submission đã commit đổi trạng thái (không phát theo outbox).
