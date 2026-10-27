# Hệ thống số hóa và quản lý chấm bài thi tự luận trực tuyến — 05. API Contracts
> Phiên bản: FINAL v1.3 | Ngày cập nhật: 2026-10-26 | Trạng thái: draft
> Hợp nhất từ 05-api-contracts-ver1.0.md và 05-api-contracts-ver1_1.md. Thay đổi cốt lõi so với v1.0: endpoint upload submission chuyển từ multipart trực tiếp sang mô hình init (JSON) → upload trực tiếp lên storage qua route pass-through → complete (JSON); bổ sung route storage pass-through và reasonCode dọn dẹp upload dang dở.
> Cập nhật v1.2 (2026-10-26): bổ sung các endpoint nội bộ mà các lớp Client trong biểu đồ lớp gọi (trạng thái, URL ANONYMIZED và finalize của Submission; danh sách số phách; exam core/scale; kiểm tra phân công; thông báo lượt chấm đã xác nhận; review do giám khảo đánh dấu từ luồng chấm), danh sách review, quy tắc idempotent và mã 202 khi đồng bộ trạng thái chưa hoàn tất.
> Cập nhật v1.3 (2026-10-26): phân công BULK theo nguyên tắc tất cả hoặc không (không còn `rejected[]`); `ReviewDetailDto` trả `graderId` cho EXAM_OFFICE và COUNCIL; giữ endpoint nội bộ `grader-flag` (ADR-016, ADR-017, ADR-018).

## Quy ước chung
Base `/api/v1`; JSON UTF-8 trừ upload/export/SSE. UUID là string UUID; datetime ISO-8601 UTC; score là JSON number nhưng backend dùng BigDecimal. Gateway yêu cầu `Authorization: Bearer <JWT>`. Nội bộ thêm `X-User-Id:string`, `X-Role:EXAM_OFFICE|GRADER|COUNCIL`, `X-Request-Id:uuid`, `X-Timestamp:epoch-ms`, `X-Identity-Signature:HMAC`. Error chuẩn: `{code:string,message:string,requestId:string,details?:object}`. Mã phổ biến: 400 VALIDATION_ERROR, 401 UNAUTHENTICATED, 403 FORBIDDEN/RESOURCE_FORBIDDEN, 404 NOT_FOUND, 409 INVALID_STATE/CONFLICT, 413 FILE_TOO_LARGE, 422 BUSINESS_RULE_VIOLATION, 500 INTERNAL_ERROR, 503 DEPENDENCY_UNAVAILABLE.

## Exam và rubric APIs
`POST /api/v1/exams` (EXAM_OFFICE, FR-001): request `{name:string,subject:string,examTime:datetime,gradeLevel?:string,scale:number,crossGradingEnabled:boolean,graderCount:int,differenceThreshold:number,templateId:uuid}`; response 201 `{examId:uuid,status:"DRAFT",...}`. Lỗi 422 khi scale/threshold/graderCount sai, 404 template.

`PUT /api/v1/exams/{examId}/questions` (EXAM_OFFICE, FR-002): request `{questions:[{orderNo:int,content:string,maxScore:number,required:boolean,criteria?:[{label:string,description?:string,maxScore:number}]}]}`; response 200 `{examId,totalMaxScore:number,validAgainstScale:boolean}`; 422 SCORE_SCALE_MISMATCH/CRITERIA_SUM_MISMATCH. Việc activate exam/rubric bị chặn nếu mismatch (BR-001).

`POST /api/v1/exams/{examId}/rubrics/import-docx` multipart `file:binary` (EXAM_OFFICE, FR-004): response 201 `{rubricId:uuid,status:"DRAFT",table:{rows:[{cells:[{text:string,rowspan:int,colspan:int}]}]},scoreSuggestions:[{questionOrder:int,maxScore:number,sourceText:string}]}`. 415 UNSUPPORTED_MEDIA_TYPE nếu không DOCX; 422 UNSUPPORTED_DOCX_TABLE cho nested/merge bất thường. Không tự ACTIVE.

`POST /api/v1/exams/{examId}/rubrics` (manual, FR-003): request `{content:object}`; response 201 `{rubricId,status:"DRAFT"}`. `POST /api/v1/exams/{examId}/rubrics/{rubricId}/activate`: request `{confirmed:boolean}`; response 200 `{status:"ACTIVE",confirmedAt:datetime}`; 422 SCORE_SCALE_MISMATCH hoặc CONFIRMATION_REQUIRED.

Internal `GET /internal/v1/exams/{examId}/core` (Result/Review, v1.2): response 200 `{examId:uuid,name:string,subject:string}`, dùng để ghi FinalResult. Internal `GET /internal/v1/exams/{examId}/scale` (Result/Review, v1.2): response 200 `{examId:uuid,scale:number}`, dùng để kiểm điểm của Hội đồng nằm trong [0, scale]. 404 NOT_FOUND cho cả hai.

## Identity/Pseudonym APIs
`POST /api/v1/exams/{examId}/candidates/import` multipart `file:CSV` (EXAM_OFFICE, FR-005): CSV header requires `candidateNumber,fullName` and accepts optional `className`; response 200 `{inserted:int,rejected:int,errors:[{row:int,code:string,message:string}]}`. Duplicate candidate numbers are returned as row errors.

`GET /api/v1/exams/{examId}/candidates?query=...` (EXAM_OFFICE): response 200 `{items:[{candidateId:uuid,candidateNumber:string,fullName:string,className?:string}]}`. Endpoint này không cho GRADER/COUNCIL.

Internal `POST /internal/v1/pseudonyms` (Submission): request `{examId:uuid,submissionId:uuid,candidateId:uuid,idempotencyKey:string}`; response 201/200 replay `{pseudonymId:uuid,value:string}`; 409 CANDIDATE_EXAM_MISMATCH. Identity sinh CSPRNG và enforce unique (FR-008).

Internal `GET /internal/v1/submissions/{submissionId}/identity` chỉ caller Result/EXAM_OFFICE-authorized flow: response `{candidateId:uuid,candidateNumber:string,fullName:string,className?:string,pseudonym?:string}`. 403 với GRADER/COUNCIL (FR-019/023).

Internal `GET /internal/v1/pseudonyms?submissionIds=<uuid,uuid,...>` (Exam & Grading, service credential, v1.2): response 200 `{items:[{submissionId:uuid,pseudonym:string}]}`. Chỉ trả số phách, không có candidate fields; dùng cho danh sách bài được giao và ngữ cảnh chấm của GRADER. Khác với `GET /internal/v1/submissions/{submissionId}/identity` ở trên, endpoint chỉ dành cho luồng ghép phách.

## Submission APIs
> Luồng upload dùng mô hình 3 bước: **init (JSON)** → **upload trực tiếp lên storage qua route pass-through** → **complete (JSON)**, thay cho multipart trực tiếp qua backend.

`POST /api/v1/exams/{examId}/submissions` (EXAM_OFFICE, FR-006/007) — init, JSON thuần: request `{candidateId:uuid, templateId:uuid, fileName:string, fileSizeBytes:long, contentType:"application/pdf"}`; response 201:
```json
{
  "submissionId": "uuid",
  "status": "UPLOADING",
  "upload": {
    "url": "https://<gateway-domain>/storage/original/{objectKey}",
    "fields": {
      "key": "original/{objectKey}",
      "policy": "base64...",
      "x-amz-signature": "...",
      "x-amz-credential": "...",
      "x-amz-date": "...",
      "x-amz-algorithm": "AWS4-HMAC-SHA256"
    }
  },
  "uploadExpiresAt": "datetime",
  "statusUrl": "string"
}
```
Lỗi 422 CANDIDATE_EXAM_MISMATCH; 422 khi `fileSizeBytes > 52428800` hoặc `contentType != application/pdf` (kiểm tra dựa khai báo; kiểm tra thật sự diễn ra ở bước VALIDATING và tại MinIO qua policy). `fields` sinh từ presigned POST policy có điều kiện `content-length-range` khớp `fileSizeBytes` (± dung sai nhỏ) và `key` cố định — client không được tự chọn object key hay vượt size đã khai báo (BR-013/014, NFR-security).

`POST /api/v1/submissions/{id}/complete` (EXAM_OFFICE): request `{}`; Submission Service HEAD object tại `object_key` đã cấp, xác minh tồn tại và `size_bytes` khớp khai báo trong dung sai cho phép. Response 200 `{submissionId, status:"UPLOADED"}`. Lỗi: 409 `UPLOAD_NOT_FOUND` nếu object chưa tồn tại; 409 `UPLOAD_EXPIRED` nếu gọi sau `uploadExpiresAt`; 422 `FILE_TOO_LARGE` nếu size thực tế vượt giới hạn (khi đó object bị xóa và `status=FAILED`). Gọi lại nhiều lần với cùng submission đã `UPLOADED` trả 200 idempotent, không lỗi (FR-006/007, FR-022).

`GET /api/v1/submissions/{submissionId}`: response `{submissionId,examId,status,pageCount?:int,progress:int,error?:{code,message},updatedAt:datetime}`. Role lọc theo resource; GRADER chỉ bài đã giao (FR-009/023).

`GET /api/v1/exams/{examId}/submissions/events` SSE (EXAM_OFFICE; grader có stream riêng theo assignment nếu cần): event `id:<sequence>`, `event:submission-status`, data `{submissionId,status,progress,updatedAt}`; hỗ trợ `Last-Event-ID`. Heartbeat comment khoảng 15s. Reconnect sau gap gọi snapshot REST (FR-009).

`POST /api/v1/submissions/{id}/files/{type}/access` với `type=ORIGINAL|ANONYMIZED`: response `{url:string,expiresAt:datetime}`. ORIGINAL chỉ EXAM_OFFICE; ANONYMIZED cho EXAM_OFFICE/COUNCIL và GRADER được assignment. 403 ORIGINAL_FORBIDDEN cho GRADER/COUNCIL. URL mặc định hết hạn 5 phút. Đây là presigned GET cho xem/tải file (khác với presigned POST upload ở trên), vẫn đi qua route storage pass-through tương tự (FR-013/023).

`POST /api/v1/submissions/{id}/retry` (EXAM_OFFICE): request `{step:"VALIDATE"|"ANONYMIZE"}`; response 202 `{jobId,status:"QUEUED"}`; 409 INVALID_STATE nếu step đã thành công/không retry được (FR-010/011).

Internal `GET /internal/v1/submissions/{id}` (Exam & Grading, Result/Review; v1.2): response 200 `{submissionId,examId,status,version:int}`; 404 NOT_FOUND.

Internal `POST /internal/v1/submissions/{id}/files/ANONYMIZED/access` (Exam & Grading, Result/Review; service credential, v1.2): response 200 `{url,expiresAt}` (5 phút). Service gọi đã kiểm quyền người dùng trước; endpoint này không bao giờ cấp ORIGINAL.

Internal `POST /internal/v1/submissions/{id}/finalize` (Result/Review, v1.2): request `{finalizedAt:datetime,retentionDeadline:datetime,idempotencyKey:string}`; response 200 `{status:"FINALIZED",version:int}`; gọi lặp với submission đã FINALIZED cùng giá trị vẫn trả 200; 409 ILLEGAL_TRANSITION nếu submission không ở COMPLETED.

## Storage pass-through route
`ANY /storage/{path+}` trên Gateway — **không phải REST API nghiệp vụ**, là route proxy thuần: Gateway forward nguyên trạng method/headers/query-string/body tới MinIO nội bộ theo path tương ứng, không parse/validate content, `proxy_request_buffering off` để stream. Route này không yêu cầu `Authorization: Bearer <JWT>` (vì presigned policy/signature trong query string đã là cơ chế xác thực cho riêng thao tác object đó) và không thêm internal identity headers HMAC. Chỉ nhận method mà presigned policy cho phép (POST cho upload theo policy đã cấp, GET cho presigned download); các method khác hoặc thiếu chữ ký hợp lệ bị MinIO từ chối trực tiếp (403 từ MinIO, không qua tầng logic của bất kỳ business service nào).

## RabbitMQ contract
Exchange `submission.processing` direct; routing key `anonymize`; durable queue `submission.anonymize.v1`. Message `{schemaVersion:1,jobId:uuid,submissionId:uuid,examId:uuid,templateId:uuid,pseudonym:string,attempt:int,correlationId:uuid}`. Không chứa candidateId/name. Consumer manual ack; lỗi transient chuyển retry queues 30s→2m→10m, tối đa 3 retry rồi FAILED; lỗi template nghiệp vụ không retry và tạo REVIEW_REQUIRED. Duplicate `jobId/submissionId+step` phải idempotent (FR-010/022).

## Assignment và grading APIs
`POST /api/v1/exams/{examId}/assignments/bulk` (EXAM_OFFICE, FR-012): request `{submissionIds:[uuid],graderIds:[string],mode:"BULK"}`; response 201 `{created:[{assignmentId,submissionId,graderId}]}`. Tất cả hoặc không (v1.3): nếu có bất kỳ bài nào không ở READY_FOR_GRADING hoặc không thuộc kỳ thi thì từ chối toàn bộ yêu cầu bằng 422 NOT_READY_FOR_GRADING, `details.submissionIds` liệt kê các bài vi phạm và không tạo assignment nào; 422 GRADER_COUNT_MISMATCH khi bật chấm chéo mà số giám khảo khác `graderCount`; 422 GRADER_DUPLICATED khi danh sách giám khảo trùng. Toàn bộ assignment được lưu trong một transaction cục bộ, sau đó Exam & Grading yêu cầu Submission chuyển READY_FOR_GRADING→GRADING cho từng bài (`expectedStatus=READY_FOR_GRADING`, có `idempotencyKey`); nếu một lần đồng bộ lỗi thì retry, assignment đã lưu được giữ nguyên.

`POST /api/v1/submissions/{submissionId}/assignments` (EXAM_OFFICE): request `{graderId:string,mode:"SINGLE_REVIEW",regrade:boolean}`; response 201 `{assignmentId}`; 409 NOT_IN_REVIEW nếu submission != REVIEW_REQUIRED (BR-019). Sau khi lưu, Exam & Grading yêu cầu Submission chuyển REVIEW_REQUIRED→GRADING (v1.2).

`GET /api/v1/grading/assignments` (GRADER): response `{items:[{assignmentId,submissionId,pseudonym:string,status,assignedAt}]}`. Identity candidate fields tuyệt đối không trả về.

`POST /api/v1/grading/assignments/{assignmentId}/attempts`: request `{}`; response 201 `{attemptId,attemptNo:int,status:"DRAFT"}`; 403 nếu assignment không thuộc user. Idempotent (v1.2): nếu assignment đã có lượt DRAFT thì trả 200 với lượt đó thay vì tạo mới. Lượt mới ghim rubric ACTIVE hiện hành (`rubric_id`). Việc chuyển submission sang GRADING đã thực hiện ở bước phân công, không làm lại ở đây.

`GET /api/v1/grading/attempts/{attemptId}/context`: response `{attemptId,submissionId,pseudonym,anonymizedFileAccessUrl:string,questions:[{questionId,content,maxScore,required}],rubric:object}`. Không trả điểm/note của grader khác hoặc lượt cũ khi regrade chưa confirm (FR-013/015/024). `rubric` là phiên bản đã ghim cho lượt chấm (`rubric_id`), không phải rubric ACTIVE tại thời điểm mở.

`PUT /api/v1/grading/attempts/{attemptId}/scores/{questionId}`: request `{score:number,note?:string,criterionScores?:[{criterionId:uuid,score:number}],needsReview:boolean}`; response 200 `{questionId,score,note?,needsReview}`; 422 SCORE_OUT_OF_RANGE/CRITERIA_SUM_MISMATCH. `needsReview=true` đồng thời gọi review/status orchestration để chuyển REVIEW_REQUIRED (FR-014/017).

`POST /api/v1/grading/attempts/{attemptId}/confirm`: request `{confirmed:true}`; response 200 `{attemptId,status:"CONFIRMED",totalScore:number,confirmedAt}`; 422 MISSING_REQUIRED_SCORE/SCORE_OUT_OF_RANGE. Sau confirm mới cho phép endpoint review-authorized hiển thị kết quả grader khác (FR-014/015). Confirm lại lượt đã CONFIRMED trả 200 với kết quả cũ (v1.2). Sau khi commit, Exam & Grading gửi thông báo bất đồng bộ, idempotent tới Result/Review (xem `attempt-confirmed` bên dưới).

Internal `GET /internal/v1/submissions/{id}/grading-snapshot`: response `{examId,crossGradingEnabled,graderCount,threshold,attempts:[{attemptId,graderId,totalScore,confirmedAt,questionScores:[{questionId,score,note?}]}]}`; chỉ Result/Review service credential.

Internal `GET /internal/v1/submissions/{submissionId}/assignments/check?graderId=<id>` (Result/Review, v1.2): response 200 `{assigned:boolean}`; dùng khi giám khảo đánh dấu vấn đề để kiểm giám khảo có được phân công bài đó.

## Review, finalization và export APIs
`POST /api/v1/submissions/{id}/reviews` (GRADER flag hoặc EXAM_OFFICE phúc khảo): request `{reasonType:"GRADER_FLAG"|"REMARK",reason:string}`; response 201 `{reviewId,status:"OPEN"}` và submission → REVIEW_REQUIRED; 422 reason trống (FR-017/024). Quy tắc (v1.2): GRADER chỉ được tạo `GRADER_FLAG` và phải được phân công bài đó (403 RESOURCE_FORBIDDEN); `REMARK` chỉ EXAM_OFFICE; nếu submission đã có Review OPEN thì 409 REVIEW_ALREADY_OPEN (kèm `details.reviewId`). Review được ghi trước, sau đó yêu cầu Submission chuyển GRADING→REVIEW_REQUIRED với `expectedStatus=GRADING`; nếu bước này lỗi thì Review vẫn được giữ, API trả 202 `{reviewId,status:"OPEN",statusSync:"PENDING"}` và việc đồng bộ được retry idempotent.

`GET /api/v1/reviews?status=OPEN` (EXAM_OFFICE/COUNCIL, v1.2): response 200 `{items:[{reviewId,submissionId,reasonType,status,openedAt}]}`; GRADER nhận 403.

`GET /api/v1/reviews/{reviewId}`: EXAM_OFFICE/COUNCIL; response `{reviewId,submissionId,reasonType,reason,status,attempts:[{label:"GK1",graderId,totalScore,questionScores:[...],notes:[string]}],anonymizedFileAccessUrl}`. EXAM_OFFICE có thể yêu cầu ORIGINAL riêng; COUNCIL không nhận danh tính thí sinh/bản ORIGINAL nhưng được xem `graderId` của từng lượt chấm (v1.3, ADR-016) (FR-017/018).

`POST /api/v1/reviews/{reviewId}/council-decision` (COUNCIL): request `{finalScore:number,reason:string}`; response 200 `{decisionId,decisionSource:"COUNCIL",finalScore,decidedAt}`; 422 REASON_REQUIRED/SCORE_OUT_OF_RANGE; 403 nếu review không phải bất đồng chuyên môn (FR-018, BR-021); 404 NOT_FOUND; 409 REVIEW_NOT_OPEN (review đã RESOLVED hoặc xung đột version). Điểm được kiểm theo `scale` của kỳ thi. CouncilDecision và việc đóng Review được lưu trong một transaction cục bộ; sau đó Result/Review yêu cầu Submission chuyển REVIEW_REQUIRED→COMPLETED (reasonCode `REVIEW_RESOLVED`). Nếu bước này lỗi thì quyết định vẫn được giữ, API trả 202 `{decisionId,statusSync:"PENDING"}` và việc đồng bộ được retry idempotent (v1.2).

Internal `POST /internal/v1/submissions/{id}/attempt-confirmed` (Exam & Grading → Result/Review, v1.2): request `{idempotencyKey:string}`; response 200 kể cả khi chưa đủ lượt hoặc không tạo Review. Result/Review lấy snapshot; đủ lượt và mọi cặp ≤ threshold thì chuyển GRADING→COMPLETED; có cặp vượt ngưỡng thì ghi Review SCORE_DIFFERENCE (`openedBy=SYSTEM`) rồi chuyển GRADING→REVIEW_REQUIRED (reasonCode `SCORE_DIFFERENCE`). Gọi lặp an toàn.

Internal `POST /internal/v1/submissions/{id}/reviews/grader-flag` (Exam & Grading, khi `needsReview=true`, v1.2): request `{graderId,reason}`; response 201 `{reviewId,status}` hoặc 200 nếu đã có Review OPEN. Cùng quy tắc với endpoint công khai nhưng `graderId` do Exam & Grading đã xác thực. Giữ cả hai đường tạo GRADER_FLAG (v1.3, ADR-018).

`POST /api/v1/submissions/{id}/finalize` (EXAM_OFFICE): request `{confirm:true}`; response 200 `{resultId,finalScore,decisionSource,status:"FINALIZED",finalizedAt,retentionDeadline}`; 409 OPEN_REVIEW/INSUFFICIENT_GRADING/INVALID_STATE. Service tính single/cross/council đúng BR-010/012/017 và snapshot identity/exam core trước finalize (FR-020/025). Quy tắc (v1.2): `finalizedAt` và `retentionDeadline = finalizedAt + 1 năm` được tính trước khi tạo FinalResult; FinalResult được lưu trong một transaction cục bộ rồi mới gọi Submission `finalize`. Gọi lại khi đã có FinalResult không tạo bản ghi mới: nếu Submission đã FINALIZED trả 200 kèm kết quả cũ; nếu còn COMPLETED thì chỉ thực hiện lại bước đồng bộ trạng thái. Nếu bước đồng bộ lỗi, trả 202 `{resultId,statusSync:"PENDING"}`. 404 NOT_FOUND nếu submission không tồn tại.

`GET /api/v1/exams/{examId}/results/export?format=xlsx|csv` (EXAM_OFFICE): response binary/stream, Content-Disposition. Trước purge gồm candidate, pseudonym, GK labels, từng câu, final, status, issue notes; sau purge chỉ có dữ liệu kết quả cốt lõi còn được giữ, vì điểm câu/phách đã bị xóa theo FR-025. 409 RESULTS_NOT_FINALIZED nếu yêu cầu không hợp lệ (FR-021).

## Retention internal APIs
Scheduler Result/Review chọn deadline hết hạn rồi gọi idempotent với `Idempotency-Key=resultId:retention:v1`: `DELETE /internal/v1/submissions/{id}/retained-details` → `{deletedObjects:int,status:"DONE"}`; `DELETE /internal/v1/grading/submissions/{id}/retained-details` → `{deletedAttempts:int,status:"DONE"}`; `DELETE /internal/v1/identity/submissions/{id}/pseudonym` → `{deletedPseudonyms:int,status:"DONE"}`. Result tự xóa review detail và giữ FinalResult. 200 cho cả replay đã xóa; 503 làm scheduler retry, không đánh dấu PURGED đến khi đủ receipts (FR-025).

## State transition internal contract
Internal `POST /internal/v1/submissions/{id}/status`: request `{expectedStatus?:string,newStatus:string,reasonCode?:string,progress?:int,finalizedAt?:datetime,idempotencyKey?:string}`; response `{submissionId,status,version:int}`; 409 STATUS_VERSION_CONFLICT/ILLEGAL_TRANSITION. Dùng optimistic version để các service không ghi đè trạng thái cạnh tranh (FR-022). Bổ sung `reasonCode` mới cho transition tự động do dọn dẹp: `UPLOAD_ABANDONED` khi scheduler chuyển `UPLOADING → FAILED` sau `uploadExpiresAt` (FR-022).

Bảng transition do các service gọi (v1.2): `READY_FOR_GRADING→GRADING` (Exam & Grading, phân công BULK); `REVIEW_REQUIRED→GRADING` (Exam & Grading, phân công SINGLE_REVIEW); `GRADING→REVIEW_REQUIRED` (Result/Review, reasonCode `GRADER_FLAG` hoặc `SCORE_DIFFERENCE`); `GRADING→COMPLETED` (Result/Review, đủ lượt và mọi cặp trong ngưỡng); `REVIEW_REQUIRED→COMPLETED` (Result/Review, reasonCode `REVIEW_RESOLVED`, sau quyết định Hội đồng); `COMPLETED→FINALIZED` qua endpoint `finalize`. Gọi lặp với cùng `idempotencyKey` và cùng đích trả 200 với trạng thái hiện tại thay vì 409.
