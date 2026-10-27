# Hệ thống số hóa và quản lý chấm bài thi tự luận trực tuyến — 09. Risks & Open Items (Final)
> Phiên bản: v1.3-final | Ngày cập nhật: 2026-10-26 | Trạng thái: draft
> Hợp nhất toàn bộ Risks & Open Items từ v1.0 và v1.1. Các mục bổ sung ở v1.1 liên quan trực tiếp đến luồng upload direct-to-storage (ADR-013).

## Risks

### Liên quan luồng upload direct-to-storage (bổ sung v1.1, ADR-013)
- **Upload dang dở/orphan object** (FR-006): Vì client tự đẩy bytes trực tiếp lên MinIO và tự báo hoàn tất qua `/complete`, có 3 kịch bản lệch trạng thái: (a) client lấy presigned policy nhưng không bao giờ upload, submission kẹt ở UPLOADING; (b) client upload xong nhưng không gọi `/complete` (mất mạng, đóng tab), object tồn tại trong MinIO nhưng không có `ExamFile` record; (c) client gọi `/complete` nhiều lần hoặc sau khi hết hạn. Giảm thiểu: `uploadExpiresAt` ngắn (10 phút) giới hạn cửa sổ presigned policy còn hiệu lực; scheduler định kỳ chuyển submission UPLOADING quá hạn sang FAILED/UPLOAD_ABANDONED; job riêng quét và xóa object không có ExamFile tương ứng sau một khoảng an toàn; endpoint `complete` idempotent để gọi lại an toàn.
- **Client khai báo sai kích thước file lúc init** (FR-006/007): `fileSizeBytes` trong request init là do client tự khai, có thể không khớp bytes thực tế upload. Giảm thiểu: presigned POST policy ràng buộc `content-length-range` nên MinIO tự chối nếu vượt quá đáng kể; bước `complete` xác minh lại `size_bytes` thực tế qua HEAD object trước khi chuyển UPLOADED, không tin tưởng khai báo ban đầu.
- **Gateway trở thành điểm chịu toàn bộ băng thông upload** (đánh đổi của ADR-013 so với phương án MinIO public riêng): nhiều upload lớn đồng thời qua route pass-through vẫn tiêu tốn connection/socket của Gateway dù không tốn CPU parse. Giảm thiểu: tách cấu hình connection pool/timeout của route `/storage/*` khỏi route API JSON; theo dõi riêng metric route này (xem 06-nfr, 07-tech-stack).

### Liên quan chấm bài, review và chốt điểm (bổ sung v1.2, ADR-014/015)
- **Mất thông báo lượt chấm đã xác nhận** (FR-016, FR-017): Exam & Grading commit lượt chấm rồi mới gửi thông báo bất đồng bộ tới Result/Review; nếu service lỗi giữa hai bước, submission kẹt ở GRADING dù đã đủ lượt. Giảm thiểu: thông báo idempotent, job reconcile định kỳ quét submission GRADING đã đủ lượt CONFIRMED; transactional outbox là phương án mạnh hơn nhưng chưa dùng (xem open item).
- **Hai giám khảo xác nhận đồng thời** (FR-016): có thể tạo trùng Review hoặc gây xung đột version khi đổi trạng thái. Giảm thiểu: partial unique index một Review OPEN cho mỗi submission, xử lý `STATUS_VERSION_CONFLICT` bằng đọc lại và thử lại, thông báo idempotent.
- **Lệch trạng thái giữa dữ liệu cục bộ và Submission** (FR-017/018/020/022): Review OPEN nhưng submission chưa REVIEW_REQUIRED; quyết định Hội đồng đã lưu nhưng submission chưa COMPLETED; FinalResult đã lưu nhưng submission chưa FINALIZED. Giảm thiểu: API trả 202, gọi lại cùng yêu cầu chỉ đồng bộ lại trạng thái, `idempotencyKey` cho mọi lời gọi đổi trạng thái, metric số bản ghi chưa đồng bộ.
- **Rubric đổi giữa lúc chấm** (FR-003, FR-014): điểm bị đối chiếu với rubric khác. Giảm thiểu: ghim `rubric_id` vào lượt chấm (ADR-014).
- **Hội đồng thiên vị theo giám khảo** (v1.3, FR-018): vì COUNCIL được xem `graderId` (ADR-016), quyết định có thể bị ảnh hưởng bởi uy tín giám khảo thay vì nội dung chấm. Giảm thiểu: quy trình vận hành yêu cầu Hội đồng nêu lý do bắt buộc (BR-011), audit mọi quyết định, giao diện hiển thị nhãn GK1/GK2 làm mặc định.
- **Tạo lượt chấm trùng** (FR-014): bấm đúp hoặc retry sinh hai lượt DRAFT. Giảm thiểu: tạo lượt idempotent theo assignment (ADR-014).

### Chung, không đổi từ v1.0
- Sai template/scan lệch có thể che thiếu thông tin nhận dạng dù PDF vẫn đọc được (FR-008/011). Giảm thiểu: validate kích thước ±5%, template version cố định, preview đối chiếu EXAM_OFFICE và chuyển REVIEW_REQUIRED khi mismatch; không dùng OCR/CV vì ngoài phạm vi.
- Lộ danh tính qua access path hoặc log là rủi ro nghiêm trọng (FR-013/023). Giảm thiểu: Identity DB riêng, GRADER không có ORIGINAL endpoint, presigned URL ngắn hạn, HMAC internal context, log redaction, negative authorization tests.
- Queue giao message lặp có thể tạo nhiều phách/object hoặc state sai (FR-010). Giảm thiểu: idempotency key, UNIQUE mapping, deterministic object key, ProcessingJob durable và manual ack.
- Consistency phân tán khi finalize/purge có thể dang dở do một service unavailable (FR-020/025). Giảm thiểu: saga retry, FinalResult snapshot trước transition FINALIZED, PurgeReceipt per service, metric overdue và reconciliation job.
- Retention có thể bị vô hiệu hóa bởi backup/log/export còn chứa dữ liệu chi tiết (FR-025). Giảm thiểu: backup retention ngắn, purge sau restore, log không chứa phách/PII/điểm câu không cần thiết, export do người dùng tải xuống nằm ngoài khả năng xóa tự động của server và cần chính sách vận hành phù hợp.
- DOCX merge/nested table phức tạp có thể không biểu diễn 1-1 như mong đợi (FR-004). Giảm thiểu: parser fail-closed với cảnh báo; luôn DRAFT và EXAM_OFFICE xác nhận hoặc nhập tay.
- SSE connection qua reverse proxy có thể bị buffering/time-out (FR-009). Giảm thiểu: tắt proxy buffering cho route SSE, heartbeat, reconnect và REST snapshot.
- Không có HA ở Docker Compose; host failure làm toàn hệ thống gián đoạn. Đây là trade-off đã chấp nhận cho MVP; dùng persistent volume/backup/restart, không tuyên bố SLA production.

## Assumptions tác động thấp

### Bổ sung v1.1
- `uploadExpiresAt` mặc định 10 phút cho presigned POST policy; job dọn upload dang dở chạy mặc định mỗi 5-15 phút; job orphan cleanup chạy mặc định mỗi giờ. Các giá trị cấu hình được, không thay đổi nghiệp vụ.

### Không đổi từ v1.0
- Retry anonymization mặc định tối đa 3 lần với backoff 30 giây, 2 phút, 10 phút; các giá trị cấu hình được. Requirements chỉ yêu cầu retry/backoff, nên chi tiết này không thay đổi nghiệp vụ.
- Presigned file URL (download) mặc định 5 phút; có thể cấu hình ngắn hơn. Quyền vẫn được kiểm trước khi cấp URL.
- Scheduler retention chạy mỗi ngày. Deadline vẫn tính chính xác theo `finalized_at + 1 năm`; job quá hạn được xử lý tại lần chạy gần nhất và có metric overdue.
- DOCX upload đề xuất giới hạn 20 MB để chống resource exhaustion; không làm thay đổi quy ước nội dung rubric.
- Pagination list mặc định 50, tối đa 200; worker concurrency/DB pool được cấu hình theo môi trường.
- Một submission có một pseudonym hiện hành; lịch sử pseudonym bị purge cùng dữ liệu chi tiết, đúng assumption của requirements-ver1.2.
- Cơ chế phát hành user/JWT nằm ngoài phạm vi; hệ thống nhận JWT hợp lệ theo issuer/public key cấu hình, đúng assumption đầu vào.

## Open items không chặn kiến trúc
Các mục không đánh dấu (v1.2) không đổi từ v1.0 sang v1.1.

- Chưa có NFR định lượng về số concurrent users/submissions, throughput, p95 latency, RPO/RTO. Trước khi triển khai production cần benchmark với PDF 50 MB/20 trang và xác lập capacity; hiện tại không cần sharding/Kubernetes.
- Chính sách thời hạn backup cụ thể chưa được yêu cầu. Cần chọn backup window không mâu thuẫn FR-025 và xác minh restore-then-purge bằng test định kỳ.
- Cơ chế danh tính phát hành JWT (Keycloak, SSO trường, hay auth module khác) chưa thuộc đặc tả. Gateway giữ chuẩn JWT Resource Server để không khóa nhà cung cấp.
- Template lifecycle/versioning chi tiết chưa được mô tả. Thiết kế yêu cầu exam tham chiếu template cố định khi bắt đầu nhận submission; thay template đang dùng nên tạo version mới để tránh tái diễn giải scan cũ.
- Dữ liệu export người dùng đã tải xuống không thể bị scheduler server thu hồi sau retention. Quy trình vận hành cần quy định nơi lưu và xóa bản export nếu môi trường thật có yêu cầu compliance rộng hơn FR-025.

- (v1.2) Chưa chốt dùng transactional outbox hay chỉ job reconcile để chống mất thông báo lượt chấm đã xác nhận.

## Giả định tác động cao cần xác nhận
Không có giả định tác động cao nào, ở cả v1.0 lẫn v1.1. Các vấn đề từng ảnh hưởng trực tiếp nghiệp vụ như phân biệt FAILED/REVIEW_REQUIRED, round HALF_UP, single-grader final score, quyền Hội đồng, phạm vi retention và thời điểm tính một năm đã được requirements-ver1.2 xác định rõ; kiến trúc không thay đổi các quyết định đó. Việc chuyển sang upload direct-to-storage ở v1.1 là thay đổi kiến trúc tầng vận chuyển (transport), không thay đổi bất kỳ quyết định nghiệp vụ nào đã chốt.

## Truy vết rủi ro đến quyết định
- Rủi ro upload dang dở/orphan object và client khai báo sai kích thước dẫn ADR-013.
- Rủi ro privacy dẫn ADR-001/009.
- Duplicate queue dẫn ADR-002.
- Bất biến file dẫn ADR-004/005.
- Rubric parsing dẫn ADR-006.
- SSE reliability dẫn ADR-007.
- Precision dẫn ADR-010.
- Retention consistency dẫn ADR-011.
- Giới hạn HA dẫn ADR-012.
- Rủi ro rubric đổi giữa lúc chấm và tạo lượt chấm trùng dẫn ADR-014.
- Rủi ro mất thông báo, lệch trạng thái giữa các service và xác nhận đồng thời dẫn ADR-015.
- Rủi ro Hội đồng thiên vị theo giám khảo dẫn ADR-016.
- Phân công BULK tất cả hoặc không dẫn ADR-017; hai đường tạo GRADER_FLAG dẫn ADR-018.

Nếu các open item về SLA, auth provider hoặc retention backup làm thay đổi boundary/technology thì phải tạo version thiết kế mới thay vì sửa trực tiếp tài liệu này.
