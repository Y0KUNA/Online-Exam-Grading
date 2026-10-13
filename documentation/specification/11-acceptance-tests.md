# 11. Acceptance Tests

> Mỗi test phải được tự động hóa (JUnit + Testcontainers: PostgreSQL, RabbitMQ, MinIO). Agent không được báo "xong" một work package nếu test của nó chưa xanh.
> Ký hiệu: `[FR/BR]` = truy vết requirements. Số liệu điểm dùng BigDecimal.

## A. Phân quyền và ẩn danh (ưu tiên cao nhất)

| ID | Given / When / Then | Truy vết |
|---|---|---|
| AT-A01 | GRADER gọi `POST /submissions/{id}/files/ORIGINAL/access` → 403 `ORIGINAL_FORBIDDEN` | BR-007, FR-023 |
| AT-A02 | GRADER gọi `files/ANONYMIZED/access` trực tiếp → 403 (phải dùng attempt context) | D-09 |
| AT-A03 | GRADER mở `context` của attempt thuộc grader khác → 403 `RESOURCE_FORBIDDEN` | FR-023 |
| AT-A04 | Quét toàn bộ response JSON của mọi endpoint GRADER/COUNCIL: không có `candidateId, candidateNumber, fullName, className` | BR-006/007 |
| AT-A05 | COUNCIL và EO `GET /reviews` thấy **mọi loại** Review; COUNCIL không thấy danh tính thí sinh, không thấy URL ORIGINAL; response có nhãn GK/CL | FR-017, D-13, D-16 |
| AT-A12 | Response gửi COUNCIL không chứa `userId`, `username`, họ tên, `employeeCode` của giám khảo; chỉ có nhãn GK/CL | D-16 |
| AT-A13 | GRADER và COUNCIL gọi `/api/v1/users/**` (trừ `/me`, `change-password`) → 403 | D-14 |
| AT-A14 | `POST /reviews/{id}/decision` và `…/council-decision` bởi EXAM_OFFICE hoặc GRADER → 403; chỉ COUNCIL | D-13 |
| AT-A06 | Gọi `/internal/**` qua Gateway → 404; gọi trực tiếp service thiếu/sai `X-Service-Signature` → 401/403 | D-02 |
| AT-A07 | Request với `X-Identity-Signature` sai, timestamp lệch > 60s, hoặc request-id lặp → 401 | ADR-009 |
| AT-A08 | Truy cập `GET /exams/{id}/candidates`, export, ghép phách bằng GRADER/COUNCIL → 403 | FR-005/019/021 |
| AT-A09 | Grader A chưa confirm: không API nào trả điểm/ghi chú của Grader B cho A | BR-009, FR-015 |
| AT-A10 | Lượt chấm lại (regrade): grader không thấy điểm cũ trước khi confirm | FR-024 |
| AT-A11 | Log của mọi service sau luồng đầy đủ không chứa: JWT, presigned URL, `fields` của policy, phách, họ tên | NFR-security |

## B. Upload và vòng đời file

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-B01 | Init với `fileSizeBytes=52428801` → 422; `contentType≠application/pdf` → 422 | FR-006 |
| AT-B02 | POST bytes qua `/storage/original` đúng policy → 204; `complete` → `UPLOADED`; sai key/vượt size/hết hạn → MinIO từ chối | ADR-013 |
| AT-B03 | `complete` khi chưa upload → 409 `UPLOAD_NOT_FOUND`; sau hạn → 409 `UPLOAD_EXPIRED`; gọi lại sau thành công → 200 idempotent | FR-006 |
| AT-B04 | Size thực tế vượt 50 MB → object bị xóa, `FAILED/FILE_TOO_LARGE` | FR-007 |
| AT-B05 | Scheduler: submission `UPLOADING` quá hạn → `FAILED/UPLOAD_ABANDONED`; object mồ côi quá 1 giờ bị xóa, chạy lại không lỗi | FR-022 |
| AT-B06 | Upload không bị chặn chờ ẩn danh: `complete` trả về ngay khi chưa có bản ANONYMIZED | FR-006 |
| AT-B07 | PDF 21 trang → FAILED (hoặc theo validation đã định) ; PDF 0 byte/hỏng → `FAILED/PDF_CORRUPT` | FR-007, BR-016 |
| AT-B08 | Trang lệch kích thước 5.1% → `REVIEW_REQUIRED` + Review `ANONYMIZATION` (`technical_code=TEMPLATE_SIZE_MISMATCH`, `detail` có `pageNumber`, `deviationPercent`), **chưa có phách**; 4.9% → qua | FR-007/011 |
| AT-B09 | Sau xử lý: SHA-256 ORIGINAL không đổi; ORIGINAL không bị ghi đè | BR-013 |

## C. Ẩn danh và phách

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-C01 | 10.000 lần sinh phách trong cùng exam không trùng; ép trùng (mock) thì retry; trùng 6 lần → 500 | BR-005 |
| AT-C02 | Gọi `POST /internal/v1/pseudonyms` (`{examId,submissionId,idempotencyKey}`, không có `candidateId`) hai lần → cùng `value`, không tạo bản ghi thứ hai | FR-008, E-14 |
| AT-C02b | Init upload: Identity binding 409 → không còn row submission; candidate đang có binding `ACTIVE` → 409 `CANDIDATE_ALREADY_BOUND`; candidate sai kỳ thi → 422 `CANDIDATE_EXAM_MISMATCH` (chi tiết binding: mục O) | E-14 |
| AT-C03 | **PDF ANONYMIZED trang 1**: giải mã ảnh nhúng; mọi pixel trong vùng mask, ngoài vùng `print` (chừa lề 2 px), bằng đúng `#FFFFFF` (sai số 0); chỉ một image XObject; văn bản chỉ là phách; không còn ảnh/annotation gốc | E-07, §7 |
| AT-C03b | Mã hóa trang 1 là Flate lossless: không có filter `DCTDecode`/`JPXDecode` trên ảnh trang 1 (cố tình đổi sang JPEG thì AT-C03 phải **fail**) | §7 |
| AT-C04 | PDF ANONYMIZED không có metadata Info/XMP, không annotation/attachment | §7 |
| AT-C05 | Message RabbitMQ `anonymize` không chứa candidateId/họ tên; `validate` không chứa pseudonym | §8 |
| AT-C06 | Giao trùng một message anonymize (duplicate delivery) → chỉ một object, một transition | FR-010 |
| AT-C07 | Lỗi transient 4 lần liên tiếp → retry 30s/2m/10m (dùng TTL rút ngắn khi test) → `FAILED/ANONYMIZE_RETRY_EXHAUSTED` | FR-010, BR-016 |
| AT-C08 | Lỗi template lúc che (PDF đọc được) → `REVIEW_REQUIRED` + Review `ANONYMIZATION` (`technical_code=TEMPLATE_MASK_ERROR`), **không retry**, ORIGINAL còn nguyên | FR-011, BR-016 |
| AT-C09 | `/complete` thành công → có message trong `submission.validate.v1` (routing key `validate`), `ProcessingJob(VALIDATE)=QUEUED`; chưa có message `anonymize` | D-18 |
| AT-C10 | Worker VALIDATE xong → publish `anonymize` **sau** khi ghi DB; giả lập publish `anonymize` thất bại → message validate không bị ack, retry, kết quả cuối không tạo hai phách và không hai job | §8 |
| AT-C11 | Lỗi transient ở VALIDATE (S3/Identity tạm thời) → retry 30s/2m/10m; hết 3 lần → `FAILED/VALIDATE_RETRY_EXHAUSTED` | FR-010 |
| AT-C12 | Message sai schema (thiếu `submissionId`, `schemaVersion=99`) → vào `submission.validate.v1.dlq`, **không retry**, tăng `mq_invalid_messages_total`, Submission không đổi trạng thái | §8 |
| AT-C13 | Hai queue có consumer pool riêng: tắt worker ANONYMIZE thì VALIDATE vẫn chạy và ngược lại | §8 |
| AT-C14 | Tắt RabbitMQ lúc `/complete`: API vẫn trả 200, `ProcessingJob=QUEUED`; khi broker lên lại, reconciler gửi message, bài đi tiếp tới `READY_FOR_GRADING` | NFR |
| AT-C15 | PDF có MediaBox 100000×100000 pt hoặc render vượt `PDF_MAX_RENDER_PIXELS` → `FAILED/PDF_CORRUPT` (`error_detail=LIMIT_EXCEEDED`), không retry, worker không OOM/crash | §7 |
| AT-C16 | PDF làm xử lý vượt `PDF_JOB_TIMEOUT_SECONDS` → job bị hủy đúng hạn, consumer vẫn nhận được message kế tiếp | §7 |

## D. Cấu hình kỳ thi, rubric, roster

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-D01 | Tổng `max_score` ≠ scale → 422 `SCORE_SCALE_MISMATCH`; activate bị chặn | BR-001 |
| AT-D02 | Tổng criteria ≠ điểm câu → 422 `CRITERIA_SUM_MISMATCH` | FR-002 |
| AT-D03 | Activate rubric khi `confirmed=false` → 422 `CONFIRMATION_REQUIRED`; import DOCX không tự ACTIVE | BR-002 |
| AT-D04 | Import DOCX có ô gộp: trả đúng `rowspan/colspan` 1-1; nested table → 422; file PDF → 415 | FR-004, BR-003 |
| AT-D05 | Roster: dòng trùng SBD bị từ chối, dòng hợp lệ khác vẫn lưu; thiếu họ tên → dòng lỗi; `errors[]` đúng số dòng | FR-005, BR-020 |
| AT-D06 | Kích hoạt rubric mới → bản ACTIVE cũ thành `SUPERSEDED`; partial unique index luôn ≤ 1 ACTIVE | §3.2 |

## E. Phân công, chấm, chấm chéo

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-E01 | Bulk assign: bài chưa READY bị từ chối **từng dòng** (docx 2.3.5), response 201 `{created:[...],rejected:[{submissionId,code:"NOT_READY_FOR_GRADING"}]}`; các bài hợp lệ vẫn được giao (D-11) | FR-012 |
| AT-E02 | Cross bật, `graderIds` ≠ `graderCount` → 422 `GRADER_COUNT_MISMATCH` | FR-012 |
| AT-E03 | `SINGLE_REVIEW` trên submission không phải REVIEW_REQUIRED → 409 `NOT_IN_REVIEW` | BR-019 |
| AT-E04 | Điểm `<0` hoặc `>max` → 422 `SCORE_OUT_OF_RANGE`; thiếu câu bắt buộc khi confirm → 422 `MISSING_REQUIRED_SCORE` | BR-008 |
| AT-E05 | Sau CONFIRMED, PUT điểm → 409; confirm lặp → 200 idempotent | FR-014 |
| AT-E06 | Sửa điểm draft nhiều lần: upsert, không tạo trùng `UNIQUE(attempt_id,question_id)` | FR-014 |

**Bảng biên chấm chéo** (threshold = 0.50, đo trên tổng điểm chính xác):

| Totals | Kết quả mong đợi |
|---|---|
| 7.00, 7.50 | Đạt (|Δ|=0.50 ≤ 0.50) → mean **7.25** |
| 7.00, 7.51 | Vượt → Review `SCORE_DIFFERENCE`, `REVIEW_REQUIRED` |
| 5.00, 5.01 | Đạt → mean 5.005 → HALF_UP → **5.01** (test này loại trừ dùng `double`/làm tròn kiểu banker) |
| 8.00, 8.50, 8.49 | Mọi cặp ≤ 0.50 (0.50, 0.01, 0.49) → mean **8.33** |
| 7.00, 7.40, 7.60 | Cặp (7.00, 7.60)=0.60 vượt → review, dù hai cặp còn lại đạt |
| 6.00 (cross tắt) | Điểm cuối **6.00**, không so sánh |

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-E07 | Chạy đúng bảng trên qua luồng thật (confirm → notify → evaluate) | FR-016, BR-010 |
| AT-E08 | Notify Result/Review gửi trùng cùng `attemptId` → chỉ đánh giá/tạo Review một lần | FR-016 |
| AT-E09 | Còn Review chưa đóng (`OPEN` hoặc `ACTION_AUTHORIZED`) → bỏ qua đánh giá (Q-05); Review đóng xong thì đánh giá lại | Q-05, D-21 |

## F. Review, Hội đồng, phúc khảo

Ma trận (D-13, D-21; `10-implementation-spec.md` §3.2): **Hội đồng quyết định mọi loại Review**. EXAM_OFFICE tạo `REMARK` và thực thi hành động Hội đồng đã cho phép. Mỗi bài tối đa một Review chưa đóng.

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-F01 | Flag không có `reason` → 422, không tạo Review, trạng thái không đổi | FR-017 |
| AT-F02 | Flag hợp lệ → Review `GRADER_FLAG` `OPEN` + `REVIEW_REQUIRED` + SSE; GRADER không flag được bài không được giao | BR-018 |
| AT-F03 | `decision` `SET_FINAL_SCORE` trên `SCORE_DIFFERENCE`: thiếu `reason` → 422 `REASON_REQUIRED`; `finalScore<0` hoặc `>scale` → 422 `SCORE_OUT_OF_RANGE`; hợp lệ → Review `RESOLVED`, `decision_source=COUNCIL`, bài `COMPLETED` | FR-018, BR-011 |
| AT-F04 | Test tham số hóa đủ ma trận: mọi tổ hợp (`reason_type` × `decisionType`) ngoài bảng §3.2 → 422 `DECISION_NOT_ALLOWED` (vd `SCORE_DIFFERENCE`+`REGRADE`, `GRADER_FLAG`+`SET_FINAL_SCORE` khi chưa chấm lại, `ANONYMIZATION/MASK_ERROR`+`FORCE_ANONYMIZE`) | §3.2 |
| AT-F05 | EO không có endpoint nhập/sửa điểm do bất đồng chuyên môn; `decision` bởi EO/GRADER → 403 | BR-021, D-13 |
| AT-F06 | Hai tài khoản COUNCIL quyết cùng lúc một Review → một 200, một 409 `REVIEW_ALREADY_DECIDED`; `decided_by` là tài khoản thắng; Review đã `RESOLVED` mà quyết lại → 409 | D-20 |
| AT-F07 | `ANONYMIZATION`/`TEMPLATE_SIZE_MISMATCH`: Hội đồng `FORCE_ANONYMIZE` → Review `ACTION_AUTHORIZED`, bài vẫn `REVIEW_REQUIRED`, chưa chạy gì. EO `retry {step:VALIDATE,force:true,reviewId}` → `VALIDATING`, bỏ qua ±5%, sinh phách, `ANONYMIZING` → `READY_FOR_GRADING`; Review `RESOLVED(FORCE_ANONYMIZE)` | T17 |
| AT-F08 | EO gọi `retry`/`replace-file`/`assignments` (SINGLE_REVIEW) khi Review chưa `ACTION_AUTHORIZED`, hoặc đúng trạng thái nhưng sai hành động (vd cho `REPROCESS` mà gọi `force`) → 409 `REVIEW_NOT_AUTHORIZED`, không đổi gì | E-17 |
| AT-F09 | `ANONYMIZATION`/`TEMPLATE_MASK_ERROR`: `REPROCESS` → EO `retry {step:ANONYMIZE,reviewId}` → `ANONYMIZING`, Review `RESOLVED(REPROCESS)`; `FORCE_ANONYMIZE` cho loại này → 422 | T18 |
| AT-F10 | `GRADER_FLAG` + `DISMISS` → `RESOLVED`, bài `GRADING`, Result đánh giá lại ngay (đủ lượt thì sang `COMPLETED`) | T15a |
| AT-F11 | `GRADER_FLAG` + `REGRADE` → `ACTION_AUTHORIZED`; EO chọn `graderId` và tạo SINGLE_REVIEW kèm `reviewId` → bài `GRADING`, lượt mới `attemptNo` tăng, `is_regrade=true`, lượt cũ giữ nguyên; response gửi COUNCIL không có `graderId`/tên giám khảo | T15b, D-16 |
| AT-F12 | Lượt chấm lại `CONFIRMED` → bài về `REVIEW_REQUIRED`, Review về `OPEN` có `regrade_attempt_id`; lúc này chỉ `SET_FINAL_SCORE` hợp lệ (`DISMISS`/`REGRADE` → 422); Hội đồng thấy mọi lượt (nhãn GK/CL) | T26 |
| AT-F13 | `retry` trên FAILED `UPLOAD_ABANDONED`, `PDF_CORRUPT` hoặc `PAGE_COUNT_INVALID` → 409 (hai loại sau dùng thay file); `retry` trên FAILED `*_RETRY_EXHAUSTED` chạy được **không cần** `reviewId` | T19 |
| AT-F14 | `GRADER_FLAG` thứ hai khi đã có Review chưa đóng → 200 `{reviewId,merged:true}`, không tạo Review mới; Hội đồng thấy ghi chú gắn nhãn `GKi`, không thấy tên | D-21, E-16 |

### Phúc khảo (REMARK), gồm cả sau FINALIZED

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-F15 | EO tạo `remark` trên `COMPLETED` → Review `REMARK` `OPEN`, `REVIEW_REQUIRED`, `previous_status=COMPLETED` | T21 |
| AT-F16 | EO tạo `remark` trên `FINALIZED` trước `retention_deadline` → `REVIEW_REQUIRED`, `FinalResult.retention_hold=true`, `previous_status=FINALIZED` | D-12, T22 |
| AT-F17 | GRADER/COUNCIL gọi `remark` → 403 | D-12 |
| AT-F18 | Hội đồng `DISMISS` phúc khảo → Review `RESOLVED`, bài về `previous_status`; nếu là `FINALIZED` thì `finalized_at`, `retention_deadline` **không đổi**, hold được gỡ | T23 |
| AT-F19 | Hội đồng `SET_FINAL_SCORE` trực tiếp → bài `COMPLETED`, `decision_source=REMARK`; EO `finalize` → `FinalResult` **version 2** (`is_current=true`), version 1 `is_current=false` giữ điểm cũ | D-15, §9.5 |
| AT-F20 | **Retention không đổi sau phúc khảo**: `Submission.finalized_at` và `retention_deadline` sau khi finalize version 2 **bằng** giá trị của lần FINALIZED đầu; `retention_deadline` của version 2 = của version 1 | D-15 |
| AT-F21 | Luồng `REGRADE`: giám khảo chấm lại không thấy điểm/ghi chú cũ; lượt cũ còn trong DB; lượt chấm lại không tham gia so sánh cặp §9.1; sau confirm Hội đồng `SET_FINAL_SCORE` → `COMPLETED` → `finalize` version 2 | FR-024 |
| AT-F22 | EO `withdraw` trước khi Hội đồng `SET_FINAL_SCORE` → T23, hủy assignment/lượt chấm lại chưa `CONFIRMED` (lượt đã `CONFIRMED` còn); sau `SET_FINAL_SCORE` → 409 | T23 |
| AT-F23 | Hai request `remark` đồng thời trên cùng submission → một thành công, một 409 `REVIEW_ALREADY_OPEN` (partial unique index, kể cả khi bỏ qua kiểm tra ứng dụng) | D-21 |
| AT-F24 | `remark` khi `purge_status ∈ {IN_PROGRESS, PURGED, FAILED}` → 409 `PURGED_DATA_UNAVAILABLE`; khi `now ≥ retention_deadline` mà chưa purge → 409 `REMARK_WINDOW_CLOSED` | §9.5 |
| AT-F25 | `REMARK_ALLOW_AFTER_FINALIZED=false` → `remark` trên `FINALIZED` trả 409; trên `COMPLETED` vẫn được | cấu hình |
| AT-F26 | `REMARK_WINDOW_DAYS=30`: ngày thứ 31 kể từ `finalized_at` đầu → 409 `REMARK_WINDOW_CLOSED`, ngày thứ 30 → được; `REMARK_WINDOW_DAYS=0` → được đến hết `retention_deadline` | Q-14 |

## G. Finalize, ghép phách, export

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-G01 | Finalize khi còn Review chưa đóng → 409 `OPEN_REVIEW`; thiếu lượt chấm → 409 `INSUFFICIENT_GRADING` | BR-012 |
| AT-G02 | Nguồn điểm đúng cho 4 trường hợp: `SINGLE_GRADER`, `CROSS_GRADING`, `COUNCIL` (SCORE_DIFFERENCE/GRADER_FLAG), `REMARK` | BR-010/012/017 |
| AT-G03 | Giả lập lỗi sau khi lưu FinalResult nhưng trước khi đổi status → gọi lại finalize thành công, **chỉ một** FinalResult | E-10 |
| AT-G04 | Finalize lặp khi đã FINALIZED → 200 cùng `resultId` | E-10 |
| AT-G05 | Lần FINALIZED đầu: `retention_deadline = finalized_at + 1 năm` tính bằng `OffsetDateTime.plusYears(1)` ở UTC (ví dụ `2028-02-29T10:00Z` → `2029-02-28T10:00Z`) | BR-022 |
| AT-G06 | Ghép phách chỉ EXAM_OFFICE; mỗi lần gọi ghi `audit_log` | FR-019 |
| AT-G07 | Export CSV: UTF-8 BOM, đủ cột GK1/GK2, nhãn đúng; XLSX mở được; `Cache-Control: no-store`; không bài nào FINALIZED → 409 | FR-021 |
| AT-G08 | Bài có 2 phiên bản `FinalResult`: export và `GET /results` chỉ dùng `is_current=true`; bài đang phúc khảo hiển thị trạng thái `REVIEW_REQUIRED` | §9.5 |
| AT-G09 | Nguồn điểm khi có nhiều `CouncilDecision` `SET_FINAL_SCORE`: lấy cái có `decided_at` mới nhất, ưu tiên hơn `CROSS_GRADING`/`SINGLE_GRADER` cũ | §9.2 |

## H. Retention

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-H01 | Đặt đồng hồ giả: trước hạn 1 giây không purge; đúng hạn thì purge | FR-025 |
| AT-H02 | Sau purge: objects ORIGINAL/ANONYMIZED, Submission/ExamFile/ProcessingJob/submission_event, Attempt/QuestionScore/Assignment, Pseudonym/History/mapping, Review/CouncilDecision, audit chi tiết đều bị xóa | FR-025 |
| AT-H03 | Sau purge **còn**: Candidate, Exam/Question/Rubric (ngữ cảnh kỳ thi/môn), FinalResult (thí sinh, kỳ thi, môn, điểm cuối, nguồn) | BR-022 |
| AT-H04 | Một service purge lỗi 503 → `purge_status` vẫn chưa PURGED; chạy lại thì hoàn tất; replay purge đã xóa → 200 | ADR-011 |
| AT-H05 | Hai submission cùng kỳ thi FINALIZED ở hai thời điểm khác nhau hết hạn **độc lập** | FR-025 |
| AT-H06 | Export sau purge: có dữ liệu cốt lõi, không có phách/điểm câu | FR-021 |
| AT-H07 | Có `retention_hold=true`: scheduler **bỏ qua** dù đã quá `retention_deadline`. Phúc khảo kết thúc và finalize lại: `retention_deadline` **vẫn là hạn gốc**; nếu hạn đã qua thì lần chạy scheduler kế tiếp purge | §9.3, §9.5 |
| AT-H08 | Race: scheduler và `remark` chạy đồng thời tại thời điểm hết hạn → đúng một bên thắng (cập nhật có điều kiện); nếu scheduler thắng thì `remark` trả 409, nếu `remark` thắng thì không purge | §9.5 |
| AT-H09 | `withdraw` phúc khảo: gỡ hold, hạn retention **cũ** giữ nguyên; nếu đã quá hạn thì lần chạy scheduler kế tiếp purge | T23 |
| AT-H10 | Phiên bản `FinalResult` cũ (`is_current=false`) **không** bị purge và không bị xuất ra export | D-15 |
| AT-H11 | Phúc khảo mở ngày 360, Hội đồng quyết ngày 380 (đã quá hạn 365): trong lúc mở không purge; sau finalize version 2 và gỡ hold → purge ở lần chạy kế tiếp; `retention_deadline` không bị kéo dài | D-15 |
| AT-H12 | Phúc khảo mở ngày 364 (hạn ngày 365): hold đặt thành công, scheduler ngày 366 bỏ qua; mở ngày 366 → 409 `REMARK_WINDOW_CLOSED`/`PURGED_DATA_UNAVAILABLE` | §9.5 |

## I. SSE và phục hồi

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-I01 | Mỗi transition: event có `id` tăng dần, được ghi cùng transaction với đổi status | FR-009 |
| AT-I02 | Ngắt kết nối rồi nối lại với `Last-Event-ID` → nhận đủ event bị lỡ, không trùng | FR-009 |
| AT-I03 | `Last-Event-ID` quá cũ → nhận `resync`, `GET snapshot` khớp trạng thái DB | FR-009 |
| AT-I04 | Heartbeat ≤ 15 giây | ADR-007 |
| AT-I05 | Khởi động lại Submission khi có job `QUEUED` dở dang → reconciler requeue, không mất bài | NFR |

## J. Hợp đồng và kiểm tra phi chức năng

- OpenAPI mỗi service được sinh/ghi tay và **contract-test** với consumer (Spring Cloud Contract hoặc Pact) cho mọi `/internal/**`.
- Upload 50 MB × 5 luồng song song qua Gateway: Submission Service heap không tăng theo kích thước file.
- `docker compose up` từ trạng thái sạch → health `UP` cho tất cả service ≤ 3 phút; chỉ Gateway publish port ra host.

## K. Người dùng và đăng nhập (`13-user-table.md`)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-K01 | Migration V1+V2 chạy sạch trên PostgreSQL 16 (Testcontainers); chạy lại trên DB trống không lỗi | D-14 |
| AT-K02 | Login đúng → JWT có `sub=user_id`, `role`, `exp=15 phút`; Gateway chấp nhận, đổi 1 byte chữ ký → 401 | D-17 |
| AT-K03 | Sai mật khẩu, user không tồn tại, `is_active=false`, đang bị khóa → **cùng** `401 INVALID_CREDENTIALS`, cùng thông điệp và (xấp xỉ) cùng độ trễ | §4.1 |
| AT-K04 | 5 lần sai liên tiếp → khóa 15 phút (`locked_until`); đăng nhập đúng trong lúc khóa vẫn 401; hết khóa đăng nhập được, `failed_login_count=0`, `last_login_at` cập nhật | §4.1 |
| AT-K05 | Username `An` và `an` → 409 `USERNAME_TAKEN`; email khác hoa/thường → 409 `EMAIL_TAKEN` | V2 |
| AT-K06 | Tạo user `role=GRADER` kèm `graderProfile` → có đúng 1 dòng `graders`; `graderProfile` cho role khác → 422; `employeeCode` trùng → 409 | §4.2 |
| AT-K07 | Ép tạo `graders` cho user `role≠GRADER` bằng SQL → vi phạm FK ghép; đổi role của user đang có hồ sơ → bị chặn | V2 |
| AT-K08 | `PATCH` đổi `role` → 409 `ROLE_CHANGE_FORBIDDEN`; vô hiệu hóa EXAM_OFFICE hoạt động cuối cùng → 409 `LAST_EXAM_OFFICE` | §4.2 |
| AT-K09 | Mật khẩu tạm chỉ xuất hiện trong response tạo/reset **một lần**; `must_change_password=true`; sau `change-password` thì `false` | §4.2 |
| AT-K10 | Không response nào chứa `password_hash`; log không chứa `password`, `password_hash`, `accessToken` | AT-A11 |
| AT-K11 | `POST /exams/{id}/graders` với user `role=EXAM_OFFICE` hoặc `is_active=false` → 422 `GRADER_NOT_REGISTERED`; bulk assignment với user không nằm trong `exam_grader` → 422 | Q-06, §5 |
| AT-K12 | `users/lookup` từ service không trong allowlist → 403; response không có `email`, `password_hash`, hồ sơ | §4.3 |
| AT-K13 | Bootstrap: DB trống + có `BOOTSTRAP_ADMIN_*` → tạo 1 EXAM_OFFICE với `must_change_password=true`; khởi động lại không tạo thêm | §5 |
| AT-K14 | `audit_log` ghi tạo/sửa/vô hiệu hóa user, reset mật khẩu; không chứa mật khẩu hay hash | §5 |

## M. Độ tin cậy liên service (`10-implementation-spec.md` §12)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-M01 | Tắt Result khi giám khảo confirm → API confirm vẫn 200, `outbox_event=PENDING`; bật lại → trong vài chu kỳ relay bài được đánh giá và sang `COMPLETED` (hoặc tạo Review) | §12 |
| AT-M02 | Gửi trùng cùng `idempotency_key` → hiệu ứng đúng một lần (một Review, một transition) | §12 |
| AT-M03 | Submission `/status` lỗi 503 ba lần rồi OK → T13 xảy ra đúng một lần; backoff đúng 5s/30s/2m | §12 |
| AT-M04 | Hai event cùng `submissionId` (confirm A rồi B), event đầu lỗi tạm → event sau **không** vượt mặt; đúng thứ tự | §12 |
| AT-M05 | Quá `OUTBOX_MAX_ATTEMPTS` → `DEAD`, `outbox_dead_total` tăng, không PII trong log; `replay` xong thì được xử lý | §12 |
| AT-M06 | Worker T08: Submission đã `REVIEW_REQUIRED`, Result đang lỗi → khi Result lên, Review `ANONYMIZATION` được tạo đúng một lần | T08/T11 |
| AT-M07 | Bulk assignment khi Submission tạm lỗi: assignments đã tạo, status lên `GRADING` sau khi Submission phục hồi; gửi lại cùng request bulk không tạo assignment trùng | T12 |
| AT-M08 | Xóa mất dòng outbox (mô phỏng sự cố) → `reconcile-grading` tìm ra bài kẹt `GRADING` và đưa đi tiếp; chạy lại không đổi gì | §12 |

## L. Thay file scan (`10-implementation-spec.md` §4.4)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-L01 | Bài `REVIEW_REQUIRED` + Review `ANONYMIZATION` đã được Hội đồng `REPLACE_FILE` (`ACTION_AUTHORIZED`): `replace-file` → 201, presigned POST key `.../v2.pdf`, **status chưa đổi**; upload + `complete` → `UPLOADED`, `current_file_version=2`, Review `RESOLVED(REPLACE_FILE)`; xử lý xong → `READY_FOR_GRADING` | D-19, T24 |
| AT-L02 | Bài `FAILED/PDF_CORRUPT` hoặc `PAGE_COUNT_INVALID`: thay file được; `FAILED/UPLOAD_ABANDONED`, `ANONYMIZE_RETRY_EXHAUSTED` → 409 `REPLACE_NOT_ALLOWED` | T25 |
| AT-L03 | Bài `COMPLETED`, `FINALIZED`, `GRADING`, Review `REMARK`/`SCORE_DIFFERENCE` → 409 `REPLACE_NOT_ALLOWED`; Review `ANONYMIZATION`/`GRADER_FLAG` chưa `ACTION_AUTHORIZED(REPLACE_FILE)` → 409 `REVIEW_NOT_AUTHORIZED` | D-19 |
| AT-L04 | GRADER/COUNCIL gọi `replace-file`, `complete`, `DELETE`, `GET files` → 403 (Hội đồng chỉ *cho phép* qua `decision`, EO mới thực thi) | ma trận quyền (file 12 §12.3) |
| AT-L05 | Bài `GRADER_FLAG` có lượt `CONFIRMED` → 409 `ATTEMPTS_EXIST` (cả lúc `replace-file` và lúc `complete`, nếu lượt được confirm trong lúc chờ upload) | Q-13 |
| AT-L06 | Bài `GRADER_FLAG` chỉ có lượt `DRAFT`: sau `complete` lượt `DISCARDED`, assignment `CANCELLED`, bài `READY_FOR_GRADING`; dữ liệu draft còn trong DB; EO giao lại được | Q-13 |
| AT-L07 | **ORIGINAL v1 không đổi**: object còn nguyên, SHA-256 trước/sau bằng nhau, `is_current=false`; ORIGINAL `v2` có `sha256` riêng sau VALIDATE | BR-013 |
| AT-L08 | **Phách không đổi** sau thay file: `pseudonym` trước/sau bằng nhau, không tạo bản ghi `Pseudonym` mới | D-10, E-14 |
| AT-L09 | GRADER chỉ nhận ANONYMIZED `v2`; không có đường nào lấy được `v1` (ORIGINAL hay ANONYMIZED); EO xem `v1` bằng `files/ORIGINAL/access?version=1`, GRADER gọi → 403 | BR-007 |
| AT-L10 | `complete` hai lần → lần hai 200 idempotent, không tạo `ProcessingJob` thứ hai; `discard-open-work` gọi lại không lỗi | idempotency |
| AT-L11 | `discard-open-work` trả 503 → `complete` trả 503, **chưa** đổi `ExamFile`/Review/status; gọi lại thành công | §4.4 |
| AT-L12 | Hai `replace-file` liên tiếp khi cái đầu còn hạn → 409 `REPLACEMENT_IN_PROGRESS`; sau `DELETE` hoặc hết hạn tạo lại được | §4.4 |
| AT-L13 | Hết hạn không `complete`: `file_replacement=EXPIRED`, submission **không đổi**, object mồ côi bị dọn; object thuộc replacement `PENDING` chưa hết hạn **không** bị dọn | §4.4 |
| AT-L14 | Message `validate` với `fileVersion=1` đến sau khi `current_file_version=2` → bị bỏ qua (ack), không đổi trạng thái | §8 |
| AT-L15 | Thay file lần 2 khi `v2` lại lỗi template (Review `ANONYMIZATION` mới, tạo được vì Review trước đã `RESOLVED`) → Hội đồng cho phép `REPLACE_FILE` lần nữa → tạo `v3`, mọi `ExamFile` cũ `is_current=false`, đúng một `is_current` mỗi type | D-19, D-21 |
| AT-L16 | Retention: sau purge, **mọi phiên bản** ORIGINAL `v1..vn` và ANONYMIZED bị xóa khỏi MinIO và DB | FR-025 |
| AT-L17 | `audit_log` có `REPLACE_FILE_REQUESTED/COMPLETED` kèm `reason`, `version`, `actor_id`; không chứa key/URL; `reason` trống → 422 | §4.4 |
| AT-L18 | `fileSizeBytes > 52428800` hoặc `contentType ≠ application/pdf` → 422; size thực tế vượt → `file_replacement=CANCELLED`, object xóa, submission giữ trạng thái cũ | FR-006/007 |

## N. Gateway `/storage` allowlist (`10-implementation-spec.md` §6.3, chạy trong spike S0)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-N01 | `GET/POST /storage/other-bucket/...` và mọi đường dẫn ngoài `original`/`anonymized` → 404 tại Gateway (MinIO không nhận request) | §6.3 |
| AT-N02 | `GET /storage/original`, `GET /storage/anonymized`, `GET /storage/` (liệt kê bucket) → 405 | §6.3 |
| AT-N03 | `PUT`, `DELETE`, `OPTIONS` tới mọi đường dẫn `/storage/**` → 405 | §6.3 |
| AT-N04 | `POST /storage/original/{objectKey}` (có key trong đường dẫn) hoặc `POST` kèm query string → 405; `POST` thiếu `multipart/form-data` → 405; body > `MAX_PDF_BYTES + 65536` → 413 | §6.3 |
| AT-N05 | `GET` kèm `?list-type=2`, `?acl`, `?uploads`, `?delete`, `?versions`, `?tagging` → 403 | §6.3 |
| AT-N06 | `GET` đúng đường dẫn nhưng thiếu `X-Amz-Signature` → 403 tại Gateway | §6.3 |
| AT-N07 | Đường dẫn không khớp `{uuid}/{uuid}/v{n}.pdf` (`..`, `%2e%2e`, `//`, UUID sai, `v0x`) → 404 | §6.3 |
| AT-N08 | `/minio/health/*`, `/minio/admin/*`, `/minio/v2/metrics/*` (kể cả qua `/storage/../minio`) → 404; cổng 9001 (console) không truy cập được từ ngoài mạng Docker | §6.3 |
| AT-N09 | `Authorization`, `Cookie`, `X-User-Id`, `X-Forwarded-For` do client gửi **không** tới MinIO (mock MinIO xác nhận); presigned GET vẫn tải được khi client gửi kèm `Authorization` | §6.3 |
| AT-N10 | Response `/storage/**` có `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, không có `Server`/`X-Minio-*` | §6.3 |
| AT-N11 | `GET` kèm `Range: bytes=0-1023` trả 206 đúng 1024 byte (PDF.js) | §6.3 |
| AT-N12 | Vượt `STORAGE_RATE_LIMIT_PER_MINUTE` → 429; log Gateway **không** chứa query string/chữ ký của `/storage` | §6.3, NFR |

## O. Binding thí sinh và bất biến một Review chưa đóng (§5.2, D-21)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-O01 | Init thành công → binding `ACTIVE`; init lần hai cho cùng candidate → 409 `CANDIDATE_ALREADY_BOUND` | E-14 |
| AT-O02 | Hai init đồng thời cho cùng candidate → đúng một thành công (unique index `ACTIVE` mỗi candidate) | §5.2 |
| AT-O03 | `FAILED/UPLOAD_ABANDONED` và `FAILED/FILE_TOO_LARGE` → binding `RELEASED` → init mới cho candidate đó thành công | T03/T04 |
| AT-O04 | `FAILED/PDF_CORRUPT`, `PAGE_COUNT_INVALID`, `*_RETRY_EXHAUSTED` và mọi trạng thái khác (kể cả `FINALIZED`) → binding **vẫn `ACTIVE`**, init mới → 409; xử lý bằng thay file/retry | §5.2 |
| AT-O05 | Identity tạm lỗi lúc release → `binding_released=false`; scheduler đối soát gửi lại tới khi thành công, rồi `binding_released=true`; gọi release hai lần → 200 | §5.2, §12 |
| AT-O06 | Binding lỗi lúc init → không còn row submission; process chết giữa insert và bind → row thành `FAILED/UPLOAD_ABANDONED` khi hết hạn, release no-op | T01 |
| AT-O07 | Binding `RELEASED` rồi gọi sinh phách → 409 `BINDING_RELEASED` | §5.2 |
| AT-O08 | Purge Identity xóa cả `submission_binding`, `Pseudonym`, `PseudonymHistory` của submission | FR-025, BR-006 |
| AT-O09 | Init gửi lại với cùng `Idempotency-Key` → cùng response, không tạo submission/binding thứ hai; khác body → 409 `IDEMPOTENCY_KEY_REUSED` | D-08 |
| AT-O10 | **DB**: chèn trực tiếp Review thứ hai `OPEN`/`ACTION_AUTHORIZED` cho cùng submission → vi phạm `uq_review_one_unclosed` | D-21 |
| AT-O11 | Bài đang có Review chưa đóng: `remark`/tạo `SCORE_DIFFERENCE` → 409 `REVIEW_ALREADY_OPEN` (SCORE_DIFFERENCE: Result bỏ qua đánh giá, không lỗi) | D-21, Q-05 |
| AT-O12 | Worker T08/T11 gặp Review chưa đóng → 409, message được ack, trạng thái không đổi, log `WARN` không PII | D-21 |
| AT-O13 | Mọi đường rời `REVIEW_REQUIRED` (T15–T18, T23, T24, T28) chỉ xảy ra khi Review duy nhất đóng/được thực thi; không có đường nào để lại Review chưa đóng mà bài đã sang `READY_FOR_GRADING`/`COMPLETED`/`FINALIZED` | D-21 |

## P. Xem trước vùng che (`10-implementation-spec.md` §4.5)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-P01 | `anonymization-preview` mặc định → 200 `image/png` của trang 1, có `X-Page-Width-Pt`, `X-Page-Height-Pt`, `X-Template-Deviation-Percent`, `X-Size-Check`, `Cache-Control: no-store`; **không** tạo object MinIO, `ExamFile`, Review hay đổi trạng thái | D-23 |
| AT-P02 | `mode=MASKED` với `dpi=ANON_RENDER_DPI` khớp **từng pixel** với trang 1 của PDF ANONYMIZED thật (cùng `PageMasker`) | §7, §4.5 |
| AT-P03 | `mode=OUTLINE`: có khung đỏ đúng vị trí tỉ lệ của mask và khung xanh của vùng `print`; nội dung gốc (có danh tính) vẫn hiển thị | §4.5 |
| AT-P04 | `override.mask` mới cho kết quả khác bản mặc định; template trong DB **không đổi**; `override` ngoài [0,1] hoặc `x+width>1` → 422 `INVALID_REGION` | §4.5 |
| AT-P05 | Sau thay file: `version=1` dựng từ ORIGINAL v1, mặc định dựng từ bản hiện hành; `version` không tồn tại → 404 | §4.4/§4.5 |
| AT-P06 | GRADER/COUNCIL gọi preview (mọi `mode`) → 403 | D-23 |
| AT-P07 | Bài `UPLOADING`/chưa có ORIGINAL → 409 `INVALID_STATE`; đã purge → 409 `PURGED_DATA_UNAVAILABLE`; PDF hỏng hoặc vượt giới hạn §7.5 → 422 `PDF_CORRUPT` | §4.5 |
| AT-P08 | Trang lệch 5.1% → `X-Size-Check: FAIL` và `X-Template-Deviation-Percent≈5.1`; vẫn trả ảnh (để EO xem trước khi quyết định `FORCE_ANONYMIZE`) | T17 |
| AT-P09 | 3 request đồng thời với `PREVIEW_MAX_CONCURRENCY=2` → request thứ ba 429 `PREVIEW_BUSY`; PDF 50 MB không làm heap tăng theo kích thước file (dùng file tạm) | §4.5, NFR |
| AT-P10 | Quá `PREVIEW_TIMEOUT_SECONDS` → hủy tác vụ, trả 503, giải phóng semaphore; file tạm bị xóa kể cả khi lỗi | §4.5 |
| AT-P11 | `audit_log` có `ANONYMIZATION_PREVIEWED` (`submissionId`, `version`, `mode`); không log/ghi lại ảnh | §4.5 |
| AT-P12 | `paper-templates/preview` với PDF mẫu + `templateId` → 200 `image/png` trang 1, có `X-Size-Check`; với `region` thuần → 200, **không** có `X-Size-Check`; thiếu cả `templateId` lẫn `region` → 422 | §4.5 |
| AT-P13 | Hoạt động khi Exam còn `DRAFT` và **không có submission nào**; không tạo `PaperTemplate`, object, hay bản ghi nào; file tạm bị xóa kể cả khi lỗi/timeout | §4.5, Q-08 |
| AT-P14 | PDF mẫu > `PREVIEW_MAX_BYTES` → 413; không phải PDF/PDF hỏng → 422 `PDF_CORRUPT`; GRADER/COUNCIL → 403 | §4.5 |
| AT-P15 | `region` ngoài [0,1] hoặc `x+width>1` → 422 `INVALID_REGION`; `audit_log` có `TEMPLATE_PREVIEWED`, không chứa tên file hay ảnh | §6.4 |

## Q. Mở lại vấn đề ẩn danh (`10-implementation-spec.md` §4.6, D-24)

| ID | Kịch bản | Truy vết |
|---|---|---|
| AT-Q01 | EO `anonymization-issues` trên bài `READY_FOR_GRADING` → 201, Review `ANONYMIZATION`/`MASK_INSUFFICIENT` `OPEN`, bài `REVIEW_REQUIRED`, SSE; `reason` được lưu và Hội đồng thấy trong chi tiết Review | T27 |
| AT-Q02 | Bài ở trạng thái khác (`UPLOADING`, `VALIDATING`, `GRADING`, `COMPLETED`, `FINALIZED`, `FAILED`…) → 409 `INVALID_STATE`; `reason` trống → 422; đã có Review chưa đóng → 409 `REVIEW_ALREADY_OPEN`; GRADER/COUNCIL gọi → 403 | D-24 |
| AT-Q03 | Gọi lại cùng `Idempotency-Key` → cùng `reviewId`, không tạo Review thứ hai; crash giữa tạo Review và đổi status → gọi lại hoàn tất, không dư Review | idempotency |
| AT-Q04 | Bài đã `REVIEW_REQUIRED` do T27 không thể bulk-assign (vào `rejected[]`); sau `DISMISS` thì giao được | D-11 |
| AT-Q05 | Hội đồng `DISMISS` → `RESOLVED(DISMISS)`, bài về `READY_FOR_GRADING`, `ExamFile` ANONYMIZED không đổi; `DISMISS` với Review `TEMPLATE_SIZE_MISMATCH`/`TEMPLATE_MASK_ERROR` → 422 `DECISION_NOT_ALLOWED` | T28, AT-F04 |
| AT-Q06 | Hội đồng `REPROCESS` → `ACTION_AUTHORIZED`, bài vẫn `REVIEW_REQUIRED`; EO `retry` đúng `reviewId` → `ANONYMIZING` → `READY_FOR_GRADING`; Review `RESOLVED(REPROCESS)` | T18 |
| AT-Q07 | **Ghi đè**: sau `REPROCESS` object ANONYMIZED **cùng key** có nội dung mới, `ExamFile.sha256`/`size_bytes` cập nhật, ORIGINAL SHA-256 không đổi; worker **không** bỏ qua vì object đã tồn tại; job gửi trùng (cùng `jobId`) chỉ chạy một lần | §8, §4.6 |
| AT-Q08 | Trong lúc `ANONYMIZING`: `files/ANONYMIZED/access` → 409 `FILE_NOT_READY`; sau khi xong, URL cấp được và trả bản mới | §4.6 |
| AT-Q09 | `retry` kèm `templateId` hợp lệ: thành công → `Submission.template_id` đổi, `Exam.template_id` không đổi, `audit_log` `TEMPLATE_CHANGED`; bài khác cùng kỳ thi không bị ảnh hưởng | Q-15 |
| AT-Q10 | `templateId` không tồn tại → 404; không `active` → 422 `TEMPLATE_INACTIVE`; kèm `FORCE_ANONYMIZE`/`FAILED` retry → 422; template mới lệch > 5% → T11 `TEMPLATE_MASK_ERROR`, `Submission.template_id` **giữ nguyên**, Review mới `OPEN` | Q-15 |
| AT-Q11 | Hội đồng `REPLACE_FILE` → EO thay file được (T24); bản ANONYMIZED cũ `is_current=false`; xử lý xong bài về `READY_FOR_GRADING` | T24 |
| AT-Q12 | Dựng lại hết retry → `FAILED/ANONYMIZE_RETRY_EXHAUSTED`; bản ANONYMIZED cũ (che thiếu) **không** cấp được cho GRADER (chưa có assignment) và EO retry lại được (T19) | T10/T19 |
| AT-Q13 | Từ `GRADING` trở đi: EO gọi `anonymization-issues` → 409 `INVALID_STATE`; giám khảo flag → Hội đồng không có lựa chọn `REPROCESS` (422 `DECISION_NOT_ALLOWED`) | giới hạn §4.6 |
