# Hệ thống số hóa và quản lý chấm bài thi tự luận trực tuyến — 08. Decisions (Final)
> Phiên bản: v1.1-final | Ngày cập nhật: 2026-09-19 | Trạng thái: draft
> Hợp nhất toàn bộ ADR từ v1.0 và v1.1. ADR-001 đến ADR-012 giữ nguyên nội dung v1.0. ADR-013 bổ sung từ v1.1.

### ADR-001: Microservice rút gọn với 4 service nghiệp vụ
- Bối cảnh: Constraint yêu cầu Gateway + Identity, Submission, Exam & Grading, Result/Review; danh tính phải tách khỏi grading (FR-008, FR-023, BR-006).
- Phương án đã xét: modular monolith; 4-service microservice; microservices chi tiết hơn.
- Quyết định: dùng đúng 4 bounded services + Gateway, database-per-service.
- Lý do: giữ isolation danh tính và ownership rõ trong khi không vượt mức phức tạp của đồ án.
- Đánh đổi/hệ quả: có consistency phân tán và internal API; không dùng distributed transaction, cần idempotency/saga cục bộ.

### ADR-002: RabbitMQ chỉ cho pipeline anonymization
- Bối cảnh: Upload không chờ che phách, cần retry/backoff (FR-006, FR-010).
- Phương án đã xét: xử lý sync; RabbitMQ work queue; Kafka/event-driven toàn hệ thống.
- Quyết định: RabbitMQ durable queue + manual ack + retry/DLX; REST cho phần còn lại.
- Lý do: đúng workload job và constraint, ít vận hành hơn Kafka.
- Đánh đổi/hệ quả: duplicate delivery có thể xảy ra nên worker phải idempotent; broker không là source of truth.

### ADR-003: PostgreSQL database-per-service
- Bối cảnh: dữ liệu quan hệ có constraint mạnh và Identity DB phải tách biệt (BR-005/006/020).
- Phương án đã xét: shared PostgreSQL; PostgreSQL database-per-service; MongoDB.
- Quyết định: PostgreSQL 16 với credential/ownership riêng cho từng service.
- Lý do: ACID, numeric, JSONB, constraints/index; phù hợp constraint.
- Đánh đổi/hệ quả: không FK/join xuyên service; query tổng hợp phải qua API/snapshot.

### ADR-004: S3-compatible Object Storage cho PDF
- Bối cảnh: PDF tối đa 50 MB, bản gốc bất biến và bản ẩn danh có quyền khác nhau (FR-006/008/013).
- Phương án đã xét: DB bytea; host filesystem; MinIO/S3.
- Quyết định: MinIO S3-compatible trong Compose, private object và presigned access ngắn hạn.
- Lý do: tách binary khỏi DB, scale/backup hợp lý, dễ chuyển S3.
- Đánh đổi/hệ quả: phải quản lý orphan object và purge phối hợp với DB.

### ADR-005: PDFBox và tọa độ mask cố định
- Bối cảnh: MVP không OCR/layout detection; template cho tọa độ tỉ lệ (FR-007/008, BR-014).
- Phương án đã xét: computer vision/OCR; iText; Apache PDFBox.
- Quyết định: PDFBox vẽ rectangle trên bản sao và ghi pseudonym.
- Lý do: Java-native, đủ cho thao tác PDF đơn giản, tránh AI/CV ngoài phạm vi.
- Đánh đổi/hệ quả: scan lệch/template mismatch cần REVIEW_REQUIRED; không thích ứng layout tự động.

### ADR-006: Apache POI cho DOCX rubric và XLSX
- Bối cảnh: FR-004 yêu cầu giữ table/merge DOCX 1-1; FR-005/021 cần XLSX.
- Phương án đã xét: Apache POI; convert DOCX/PDF sang text; dịch vụ parser ngoài.
- Quyết định: Apache POI XWPF/XSSF/SXSSF, Commons CSV cho CSV.
- Lý do: truy cập cấu trúc Word/Excel trực tiếp, không suy luận layout.
- Đánh đổi/hệ quả: nested/merge bất thường bị từ chối và chuyển nhập tay; cần giới hạn file để tránh parser abuse.

### ADR-007: SSE thay polling/WebSocket
- Bối cảnh: trạng thái chỉ cần server→client và FR-009 cấm polling định kỳ.
- Phương án đã xét: polling; WebSocket; SSE + REST snapshot.
- Quyết định: SSE có event id/heartbeat, snapshot là source of truth khi reconnect.
- Lý do: giao thức đơn giản đúng hướng dữ liệu và yêu cầu.
- Đánh đổi/hệ quả: cần quản lý connection limit/proxy buffering; không dùng SSE như nguồn dữ liệu durable.

### ADR-008: Java/Spring backend và React frontend
- Bối cảnh: cần REST/SSE, RabbitMQ, PDFBox, POI, BigDecimal và UI PDF tương tác.
- Phương án đã xét: Java/Spring + React; Node/Nest + React; .NET + React.
- Quyết định: Java 21/Spring Boot + Spring Cloud Gateway; React/TypeScript/PDF.js.
- Lý do: hệ sinh thái thư viện phù hợp nhất với document processing và typed business rules.
- Đánh đổi/hệ quả: JVM containers nặng hơn Node; phải cấu hình heap phù hợp file processing.

### ADR-009: Internal identity headers phải có HMAC và service vẫn authorize resource
- Bối cảnh: Gateway forward userId/role nhưng FR-023 yêu cầu từng service authorize theo resource.
- Phương án đã xét: header tin cậy không ký; HMAC signed headers; mTLS/service mesh.
- Quyết định: Gateway ký context bằng HMAC-SHA256 + timestamp/requestId; service xác chữ ký/replay window rồi kiểm ownership.
- Lý do: giảm spoofing nhưng vẫn phù hợp Docker Compose, không kéo service mesh vào MVP.
- Đánh đổi/hệ quả: cần quản lý/rotate shared secret; không mạnh bằng mTLS trong môi trường zero-trust lớn.

### ADR-010: Tính điểm bằng BigDecimal và HALF_UP
- Bối cảnh: FR-016/BR-010 quy định pairwise threshold, mean và làm tròn half-up 2 chữ số.
- Phương án đã xét: floating point double; decimal/BigDecimal.
- Quyết định: BigDecimal, so sánh trên tổng điểm chính xác, chỉ làm tròn final mean scale 2 bằng HALF_UP.
- Lý do: tránh sai số nhị phân ảnh hưởng quyết định review.
- Đánh đổi/hệ quả: code phải thống nhất scale/rounding và API test boundary.

### ADR-011: FinalResult snapshot và retention saga idempotent
- Bối cảnh: FR-025 yêu cầu xóa dữ liệu chi tiết từng submission sau một năm nhưng giữ candidate/exam/final score lâu dài.
- Phương án đã xét: DB cascade tập trung; object lifecycle độc lập; Result-orchestrated purge xuyên service.
- Quyết định: khi finalize tạo FinalResult core snapshot; Result scheduler gọi purge idempotent từng owner và lưu PurgeReceipt.
- Lý do: database-per-service không cho cascade xuyên service; receipt làm việc xóa có thể retry và kiểm chứng.
- Đánh đổi/hệ quả: có cửa sổ eventual purge nếu service down; cần metric overdue và backup policy tránh phục hồi dữ liệu quá hạn.

### ADR-012: Docker Compose, không Kubernetes/Redis/Kafka
- Bối cảnh: constraint hạ tầng là đồ án Docker Compose, chưa có yêu cầu HA/throughput định lượng.
- Phương án đã xét: Compose; Kubernetes; thêm cache/event platform.
- Quyết định: Compose với restart/volume; scale worker khi cần, không thêm Redis/Kafka/K8s.
- Lý do: giảm vận hành và tuân nguyên tắc không over-engineering.
- Đánh đổi/hệ quả: không có self-healing/HA đa node; nếu mục tiêu production/SLA thay đổi cần ADR mới.

### ADR-013: Upload PDF direct-to-storage qua Gateway pass-through, thay vì qua Submission Service
- Bối cảnh: Luồng v1.0 để Submission Service nhận toàn bộ multipart PDF (đến 50MB) rồi mới ghi vào MinIO, khiến service phải buffer/stream bytes trong tầng logic nghiệp vụ dù không cần đọc nội dung file tại thời điểm nhận — tốn heap/CPU khi nhiều upload đồng thời, không cần thiết cho một hạ tầng chỉ đóng vai trò trung chuyển tới object storage.
- Phương án đã xét:
  1. Giữ nguyên multipart qua Submission Service (v1.0).
  2. Presigned URL để browser upload trực tiếp, qua route pass-through trên Gateway (MinIO vẫn hoàn toàn internal — "Option A").
  3. Presigned URL để browser upload trực tiếp, MinIO có domain/port public riêng, không qua Gateway ("Option B").
- Quyết định: chọn phương án 2 — Submission Service sinh presigned POST policy (không phải PUT thô) cho object key xác định trước; Web Client POST bytes theo policy đó tới route `/storage/*` trên Gateway; Gateway forward nguyên trạng (không parse) tới MinIO nội bộ; Submission Service xác minh kết quả bằng HEAD object sau khi client báo hoàn tất qua endpoint `complete` mới.
- Lý do:
  - Bytes không còn chạm tầng logic Java của Submission Service, giải quyết đúng vấn đề hiệu năng/tài nguyên nêu ở bối cảnh.
  - Giữ nguyên tuyên bố trust boundary đã có ("chỉ Gateway expose ra host") — không mở thêm domain/port, không cần cấu hình CORS cho MinIO, không tăng bề mặt tấn công của MinIO ra Internet (loại phương án 3 vì lý do này).
  - Presigned POST + policy cho phép MinIO tự thực thi điều kiện size/key trước khi nhận bytes, mạnh hơn việc chỉ kiểm tra ở tầng ứng dụng sau khi nhận xong.
- Đánh đổi/hệ quả:
  - Upload không còn là một request đồng bộ duy nhất mà tách thành 3 bước (init/upload/complete), cần xử lý trường hợp client bỏ dở giữa chừng (orphan object, submission kẹt ở UPLOADING) — cần thêm scheduler dọn dẹp (xem 09-risks-open-items-final).
  - `ExamFile.sha256` không còn tính được ngay lúc nhận file; dời sang bước VALIDATING — thay đổi nhỏ trong data model (04-data-model ver1.1).
  - Gateway vẫn phải xử lý băng thông upload (khác với Option B), nên không đạt mức "hoàn toàn offload khỏi Gateway" — được chấp nhận vì lợi ích bảo mật của việc giữ một cửa duy nhất được đánh giá cao hơn ở quy mô MVP.
  - Nếu tương lai cần scale upload throughput vượt khả năng Gateway (không phải bối cảnh MVP hiện tại), cần ADR mới để cân nhắc lại Option B hoặc CDN/edge upload.
