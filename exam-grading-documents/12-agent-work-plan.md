# 12. Kế hoạch làm việc cho Coding Agent

## 12.1 Prompt khởi đầu (dán nguyên văn cho agent)

```
Bạn là kỹ sư backend. Hãy xây dựng backend "Hệ thống số hóa và quản lý chấm bài thi tự luận trực tuyến"
theo bộ tài liệu trong thư mục exam-grading-spec/.

Thứ tự ưu tiên khi mâu thuẫn:
  1) source/requirements-ver1_2.json  2) 10-implementation-spec.md  3) source/02..09

Quy tắc bắt buộc:
- Đọc README.md, 10-implementation-spec.md, 11-acceptance-tests.md, 12-agent-work-plan.md, 13-user-table.md trước khi viết code.
- Làm tuần tự theo work package WP0..WP9. Chỉ chuyển WP khi toàn bộ test của WP hiện tại xanh.
- Mục [CẦN XÁC NHẬN] thì dùng giá trị mặc định đã ghi, đặt sau cấu hình, và ghi vào docs/ASSUMPTIONS.md.
- Schema Identity DB (`users`, `graders`) đã do chủ dự án tạo: dùng `sql/identity/V1__users_graders.sql` nguyên trạng, thêm V2 (đề xuất) sau khi test migration. `users.user_id` là ID người dùng duy nhất ở mọi service.
- Không truy vấn DB của service khác. Không để GRADER/COUNCIL nhận được danh tính hoặc bản ORIGINAL.
- Không đưa JWT, presigned URL, phách, họ tên, điểm từng câu vào log.
- Không thêm công nghệ ngoài 07-tech-stack (không Kafka, Redis, Kubernetes).
- Cuối mỗi WP: cập nhật OpenAPI, Flyway migration, README của service, và báo cáo ngắn gồm: đã làm,
  test đã chạy (kèm kết quả), điểm lệch so với đặc tả (nếu có), câu hỏi còn mở.
- Nếu gặp mâu thuẫn không giải quyết được bằng thứ tự ưu tiên, DỪNG và hỏi, không tự chọn.
```

## 12.2 Work packages

| WP | Nội dung | Phụ thuộc | Definition of Done (test ở file 11) |
|---|---|---|---|
| **S0 (spike)** | Compose tối thiểu: Gateway (Spring Cloud Gateway) + MinIO. Chứng minh presigned POST/GET qua `/storage/**` (E-01, §6.3) **và toàn bộ bảng allowlist**. Báo cáo cấu hình cuối cùng (Host header, StripPrefix, buffering, Range). | — | AT-B02 pass với 50 MB; AT-N01..AT-N12; throwaway code được phép, nhưng cấu hình chốt phải đưa vào WP0 |
| **WP0** | Scaffold monorepo, `libs/common` (HMAC user + service signature, error model, pagination, idempotency, audit, correlation id, log redaction), Gateway đầy đủ (JWT verify bằng JWKS của Identity, chặn `/internal/**`, route công khai `POST /api/v1/auth/login` + `/.well-known/jwks.json`, rate/size limit, route `/storage` với allowlist §6.3), **outbox + relay + idempotency (§12)**, `docker-compose.yml`, `.env.example` | S0 | AT-A06, AT-A07; `docker compose up` xanh (J) |
| **WP1** | **Identity**: migration V1+V2, `users`/`graders`, đăng nhập + JWT (RS256, JWKS), quản trị người dùng, bootstrap admin (`13-user-table.md`); roster import CSV/XLSX, candidate CRUD, pseudonym (D-04), lookup theo lô, `identity` endpoint cho EO, history, purge endpoint, `users/lookup` | WP0 | AT-K01..AT-K14, AT-C01, AT-C02, AT-D05, AT-A04, AT-A08, AT-A13, AT-O07, AT-O08 |
| **WP2** | **Exam & Grading (cấu hình)**: exam, question/criteria, rubric (manual + DOCX), `exam_grader` (kiểm user qua Identity `users/lookup`), activate/close, internal `GET /exams/{id}` | WP0, WP1 | AT-D01..AT-D04, AT-D06, AT-K11 |
| **WP3** | **Submission (đường upload + trạng thái)**: PaperTemplate API, init/complete, state machine (T01–T05, T20 hợp lệ guard), SSE + `submission_event`, scheduler abandoned/orphan, file access (EO), **thay file scan** (`replace-file`, `file_replacement`, ExamFile phiên bản), internal `/status`, `submission-bindings` + release (E-14, §5.2), `Idempotency-Key` cho init | WP1, WP2 | AT-B01..AT-B06, AT-I01..AT-I04, AT-A01, AT-C02b, AT-L01..AT-L05, AT-L07, AT-L09, AT-L12, AT-L13, AT-L17, AT-L18, AT-O01..AT-O06, AT-O09 |
| **WP4** | **Worker** VALIDATE + ANONYMIZE: RabbitMQ topology §8 (hai queue riêng, retry riêng, DLQ, validate schema message), PDF validation, `PageMasker` (rasterize-and-mask §7), retry/backoff, reconciler, `fileVersion` + `force` guard; **xem trước vùng che** (`anonymization-preview` và `paper-templates/preview`, §4.5, dùng chung `PageMasker`) | WP3 | AT-B07..AT-B09, AT-C03..AT-C16, AT-I05, AT-L08, AT-L14, AT-L15, AT-P01..AT-P15 |
| **WP5** | **Exam & Grading (chấm)**: bulk/single assignment, attempts, context (gọi Submission + Identity lookup), score PUT/confirm, snapshot, notify Result, `attempt-summary`, `discard-open-work` | WP4 | AT-E01..AT-E06, AT-A02, AT-A03, AT-A09, AT-A10, AT-L06, AT-L10, AT-L11, AT-M07 |
| **WP6** | **Result/Review (so sánh + review)**: `attempts-confirmed`, evaluate §9.1, tạo Review (bất biến một Review chưa đóng, D-21), GRADER flag + gộp ghi chú, review list/detail (EO và COUNCIL thấy mọi loại, COUNCIL không danh tính), `POST /reviews/{id}/decision` theo ma trận §3.2 (`ACTION_AUTHORIZED`, khóa lạc quan), kiểm quyền thực thi cho `retry`/`replace-file`/`SINGLE_REVIEW` (`REVIEW_NOT_AUTHORIZED`), `anonymization-issues` + `MASK_INSUFFICIENT` (T27/T28, §4.6), `retry` kèm `templateId` và ghi đè bản ẩn danh (phối hợp worker WP4), `reconcile-grading`, endpoint replay outbox | WP5 | AT-E07..AT-E09, AT-F01..AT-F14, AT-O10..AT-O13, AT-A05, AT-A12, AT-A14, AT-M01..AT-M06, AT-M08, AT-Q01..AT-Q13 |
| **WP7** | **Finalize + phúc khảo + ghép phách + export**: saga §9.2 với `FinalResult` có phiên bản, `remark`/`withdraw` §9.5 (gồm sau FINALIZED, `retention_hold`, **không tính lại** `retention_deadline`, `REMARK_WINDOW_DAYS`), chấm lại trong phúc khảo (T26), ghép phách, export CSV/XLSX §9.4 | WP6 | AT-F15..AT-F26, AT-G01..AT-G09 |
| **WP8** | **Retention**: scheduler (bỏ qua `retention_hold`, cập nhật có điều kiện, hạn không đổi sau phúc khảo), 4 lệnh purge (xóa mọi phiên bản file và `submission_binding`), `PurgeReceipt`, audit purge | WP7 | AT-H01..AT-H12, AT-L16 |
| **WP9** | **Hardening**: ma trận phân quyền tự động (mọi endpoint × 3 role), contract test internal API, quét log PII (AT-A11), tải upload (mục J), tài liệu vận hành (backup/restore-then-purge) | WP8 | AT-A04, AT-A11, mục J |

Mỗi WP kết thúc bằng một commit/PR riêng để dễ review.

## 12.3 Ma trận phân quyền (agent phải sinh test tự động từ bảng này)

| Endpoint (nhóm) | EO | GR | CO |
|---|:-:|:-:|:-:|
| Exam/rubric/question/template/roster/grader registry | ✔ | ✘ | ✘ |
| Upload init/complete/retry, **thay file scan**, danh sách submission/phiên bản file, SSE | ✔ | ✘ | ✘ |
| `files/ORIGINAL|ANONYMIZED/access` (public) | ✔ | ✘ | ✘ (COUNCIL nhận URL ANONYMIZED trong chi tiết Review) |
| Bulk assignment, hủy assignment | ✔ | ✘ | ✘ |
| `SINGLE_REVIEW` assignment, `retry`/`replace-file` cho Review | ✔ (chỉ khi Review `ACTION_AUTHORIZED` đúng hành động) | ✘ | ✘ |
| `GET /grading/assignments`, attempts, scores, confirm, context | ✘ | ✔ (chỉ bài được giao) | ✘ |
| `POST /submissions/{id}/reviews` loại `GRADER_FLAG` | ✘ | ✔ (chỉ bài được giao) | ✘ |
| `POST /submissions/{id}/remark`, `reviews/{id}/withdraw` | ✔ | ✘ | ✘ |
| `POST /submissions/{id}/anonymization-issues` | ✔ | ✘ | ✘ |
| `GET /reviews`, `GET /reviews/{id}` | ✔ (mọi loại) | ✘ | ✔ (mọi loại; không danh tính thí sinh/giám khảo, không URL ORIGINAL) |
| `POST /reviews/{id}/decision` (và bí danh `council-decision`) | ✘ | ✘ | ✔ (một lần/Review, `reason` bắt buộc) |
| `anonymization-preview`, `paper-templates/preview` | ✔ | ✘ | ✘ |
| Quản trị người dùng `/api/v1/users/**`, reset mật khẩu | ✔ | ✘ | ✘ |
| `/api/v1/auth/login`, JWKS | công khai | công khai | công khai |
| `GET /users/me`, `/users/me/change-password` | ✔ | ✔ | ✔ |
| `identity` (ghép phách), pseudonym edit | ✔ | ✘ | ✘ |
| finalize, results list, export | ✔ | ✘ | ✘ |
| `/internal/**` | chỉ service được allowlist | | |

## 12.4 Những việc agent **không** làm
- Không làm Web Client. JWT do Identity Service phát hành (D-17); không tạo service riêng.
- Không OCR, không phát hiện layout, không AI chấm điểm.
- Không đổi quyết định đã chốt trong ADR-001..013 (ADR-002 đã cập nhật theo D-18) và D-01..D-23 của file 10 mà không hỏi.
