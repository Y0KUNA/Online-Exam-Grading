# Hệ thống số hóa và quản lý chấm bài thi tự luận trực tuyến — 07. Tech Stack
> Phiên bản: FINAL (hợp nhất v1.0 + v1.1) | Ngày cập nhật: 2026-09-19 | Trạng thái: draft
> Hợp nhất từ 07-tech-stack-ver1.0.md và 07-tech-stack-ver1_1.md. Thay đổi cốt lõi so với v1.0: các mục Frontend, Object Storage, PDF processing, Authentication/authorization và Deployment cập nhật theo luồng presigned POST direct-to-MinIO qua Gateway pass-through (ADR-013).

## Backend
Chọn Java 21 LTS + Spring Boot 3.x cho bốn service và Spring Cloud Gateway cho Gateway. Lý do: hệ sinh thái tốt cho REST/SSE, validation/security, Spring AMQP, PostgreSQL, scheduler; Apache POI/PDFBox tự nhiên với Java; LTS phù hợp đồ án cần ổn định. Phương án cân nhắc: Node.js/NestJS đơn giản cho web API nhưng xử lý DOCX/PDF và typed domain/BigDecimal kém thuận lợi hơn; .NET 8 tốt tương đương nhưng không tận dụng Apache POI/PDFBox trực tiếp. Quyết định hỗ trợ FR-004, FR-008..010, FR-016.

## Frontend
Chọn React 19 + TypeScript + Vite, PDF.js cho viewer. Lý do: UI grading side-by-side có state tương tác cao; PDF.js render scan trực tiếp, zoom/page navigation mà không cần OCR. Phương án Vue cũng phù hợp nhưng không có lợi thế rõ ràng; server-rendered MVC ít phù hợp với SSE/viewer/chấm autosave. Đáp ứng FR-009, FR-013/014.

Web Client cần thêm logic gọi presigned POST (multipart-form với `fields` do backend cấp ở bước init) thẳng tới route `/storage/*`, tách khỏi client REST thông thường — khác header, khác cách xử lý lỗi, vì lỗi từ MinIO không theo format error chuẩn `{code,message,requestId}` của backend.

## Database
Chọn PostgreSQL 16, mỗi service sở hữu database/schema credential riêng và tuyệt đối không cross-query. Lý do: transaction, constraints/partial index, JSONB cho rubric grid, numeric chính xác cho điểm. MySQL được cân nhắc nhưng PostgreSQL mạnh hơn cho JSONB/partial index; MongoDB bị loại vì dữ liệu nghiệp vụ có quan hệ/constraint chặt. Đáp ứng database-per-service constraint, BR-001/005/020.

## Message broker
Chọn RabbitMQ 4.x (hoặc stable version tương thích Spring AMQP tại thời điểm build) cho anonymization job. Lý do: work queue, ack/retry/DLX phù hợp tác vụ PDF; constraint đã chỉ định RabbitMQ. Kafka bị loại vì throughput event-streaming và vận hành phức tạp không cần thiết cho một pipeline job (FR-010, NFR-availability).

## Object Storage
Chọn MinIO S3-compatible cho Docker Compose; abstraction theo S3 API để chuyển AWS S3 nếu triển khai cloud. Upload ORIGINAL dùng **presigned POST + policy** (`PostPolicy` trong MinIO SDK) thay vì presigned PUT thô, vì policy cho phép ràng buộc `content-length-range` và `key` cố định được MinIO tự thực thi ở tầng storage — không phụ thuộc Submission Service phải "đang chạy" hay "đang theo dõi" trong lúc client upload. MinIO **không** có domain/port public riêng; mọi truy cập từ browser đi qua route pass-through trên Gateway (xem mục Deployment). Lý do tổng thể: PDF đến 50MB không phù hợp DB; private bucket/presigned URL và object lifecycle dễ kiểm soát.

Phương án đã xét thêm: MinIO expose domain/port public riêng để browser gọi hoàn toàn trực tiếp (Option B) — bị loại vì mở thêm bề mặt tấn công (CVE riêng của MinIO, cần CORS, cần TLS cert riêng, lộ topology hạ tầng lưu trữ) mà không mang lại lợi ích hiệu năng đáng kể ở quy mô MVP Docker Compose (xem ADR-013). Local filesystem bị loại vì coupling host, khó scale worker/backup; lưu bytea PostgreSQL bị loại vì tăng tải DB. Đáp ứng FR-006/008/013/025.

## PDF processing
Chọn Apache PDFBox 3.x để kiểm PDF, kích thước/trang, copy document, phủ rectangle và ghi pseudonym trên bản sao. Lý do: thao tác tọa độ cố định đúng MVP, không cần OCR/image CV. iText được cân nhắc nhưng licensing cần chú ý; external PDF command-line tool tăng sandbox/process complexity. Bước tính SHA-256 diễn ra tường minh trong worker VALIDATING cùng lúc PDFBox đọc file để kiểm số trang/kích thước, không cần thêm thư viện, chỉ dời thời điểm gọi so với việc tính lúc nhận file. Đáp ứng FR-007/008 và BR-013/014.

## DOCX/Excel/CSV
Chọn Apache POI 5.x: XWPF để đọc table/merge DOCX và XSSF/SXSSF cho XLSX roster/export. CSV dùng Apache Commons CSV. Lý do: Java-native, cấu trúc Word table tường minh và streaming export. PDF parser/OCR bị loại theo phạm vi. Regex suggestion dùng Java Pattern với biểu thức nghiệp vụ đã quy định (FR-004/005/021).

## Authentication/authorization
Gateway dùng Spring Security Resource Server để validate JWT; issuer/JWKS hoặc public key cấu hình ngoài vì cơ chế cấp token ngoài phạm vi. Internal identity context ký HMAC-SHA256 với secret xoay được; service kiểm timestamp/replay window và resource ownership. mTLS được cân nhắc nhưng quá nặng cho Docker Compose MVP; header không ký bị loại vì service cần phòng spoofing. Đáp ứng FR-023, NFR-security.

Route pass-through `/storage/*` không dùng cơ chế JWT/HMAC nội bộ — nó dựa hoàn toàn vào chữ ký của presigned policy (AWS Signature V4 do MinIO SDK sinh), là một cơ chế xác thực/ủy quyền tách biệt, có phạm vi hẹp (đúng 1 object, đúng điều kiện, có TTL) hơn JWT/HMAC dùng cho phần API còn lại.

## Realtime
Chọn HTTP Server-Sent Events qua Spring MVC/WebFlux streaming, không WebSocket. Lý do: yêu cầu chỉ server→client trạng thái và input chỉ định SSE; reconnect/Last-Event-ID đơn giản hơn. Polling bị loại theo FR-009; WebSocket hai chiều không cần thiết.

## Deployment
Docker + Docker Compose v2 cho Web, Gateway, 4 services, RabbitMQ, PostgreSQL logical instances/databases và MinIO. Chỉ Gateway/Web expose public; management ports bind localhost/admin network trong development. Kubernetes bị loại theo constraint và không tạo giá trị cho MVP. Reverse proxy TLS có thể dùng Nginx/Caddy ở môi trường triển khai; trong đồ án Gateway có thể đứng sau TLS terminator.

Gateway cấu hình thêm 1 route pass-through `path /storage/** → http://minio:9000` (ví dụ với Nginx: `proxy_pass`, `proxy_request_buffering off`, `client_max_body_size` khớp giới hạn 50MB; với Spring Cloud Gateway: route `Path=/storage/**` kèm filter `StripPrefix` và cấu hình streaming). MinIO container không publish port ra host trong production, chỉ trong docker network nội bộ — network alias `minio` chỉ resolve được từ trong Compose network, đúng lý do vì sao request phải đi qua Gateway thay vì trỏ thẳng.

## Migrations và build
Flyway cho schema migration từng DB; Maven cho Java builds; npm/pnpm cho frontend. Flyway được chọn thay auto-DDL để version schema truy vết được. OpenAPI 3.1 sinh tài liệu contract từ spec/annotation nhưng không thay design contract. Testcontainers được đề xuất cho integration tests PostgreSQL/RabbitMQ/MinIO.

## Observability
Micrometer + Spring Boot Actuator; Prometheus/Grafana tùy môi trường. Log JSON qua SLF4J/Logback với correlation id và redaction. ELK/OpenSearch không bắt buộc vì tăng tài nguyên Compose; file/stdout logs đủ cho MVP nếu có retention và bảo vệ dữ liệu nhạy cảm.

Bổ sung metric cho route storage pass-through (bytes qua route này, thời gian, tỷ lệ lỗi MinIO trả về) tách khỏi metric route API JSON để không làm nhiễu số liệu latency của phần còn lại.

## Nguyên tắc version
Các major line ở trên là baseline thiết kế, minor/patch được pin trong build sau khi kiểm compatibility/security. Không phụ thuộc tính năng proprietary để giữ khả năng chạy local. Stack này ưu tiên độ đơn giản vận hành, thư viện phù hợp tài liệu/PDF và các constraint có sẵn thay vì thêm Redis/Kafka/Kubernetes không có yêu cầu.
