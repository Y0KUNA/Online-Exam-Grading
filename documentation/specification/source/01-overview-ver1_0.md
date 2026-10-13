# Hệ thống số hóa và quản lý chấm bài thi tự luận trực tuyến — 01. Overview
> Phiên bản: v1.0 | Ngày tạo: 2026-09-19 | Trạng thái: draft

## Mục tiêu
Hệ thống Web số hóa vòng đời chấm bài tự luận viết tay từ PDF scan: tiếp nhận và gán thí sinh, cắt phách điện tử, phân công, chấm độc lập/chấm chéo, review, ghép phách, chốt và xuất kết quả. Hệ thống không OCR/AI để phân tích bài hoặc quyết định điểm; quyết định chuyên môn thuộc giám khảo/Hội đồng. Thiết kế bám FR-001..FR-025 và các ràng buộc microservice, PostgreSQL database-per-service, RabbitMQ, S3-compatible storage, SSE, Docker Compose.

## Giá trị nghiệp vụ
Giảm luân chuyển bài giấy, bảo vệ danh tính thí sinh khi chấm, cung cấp trạng thái xử lý truy vết được, hỗ trợ chấm chéo nhất quán và giữ bản scan gốc bất biến trong thời hạn retention. Phách tách khỏi dữ liệu chấm (FR-008, FR-019, BR-006), điểm chỉ FINALIZED khi đủ điều kiện (FR-020), và dữ liệu chi tiết bị xóa một năm sau FINALIZED trong khi kết quả cốt lõi được giữ lâu dài (FR-025, BR-022).

## Phạm vi trong MVP
Bao gồm quản lý kỳ thi/câu hỏi/rubric; import rubric DOCX và roster CSV/XLSX; upload PDF tối đa 50 MB, 1–20 trang; kiểm tra dung sai trang ±5%; sinh phách CSPRNG; che vùng phách cố định; queue/retry; SSE; phân công; nhập/xác nhận điểm; chấm chéo; REVIEW_REQUIRED; phúc khảo/chấm lại; quyết định Hội đồng; ghép phách; FINALIZED; Excel/CSV; retention tự động (FR-001..FR-025).

## Ngoài phạm vi
Tự phát hiện layout/vùng phách, tự chia câu trên scan, import rubric từ PDF, mobile app, Kubernetes, dashboard phân tích nâng cao hoặc tích hợp đa trường. Cơ chế phát hành tài khoản/JWT nằm ngoài đặc tả; Gateway chỉ xác minh JWT đã được cấp.

## Actors
- Phòng khảo thí (EXAM_OFFICE): quản lý kỳ thi/rubric/roster, upload/gán thí sinh, xem bản gốc và ẩn danh, phân công, xử lý review kỹ thuật/quy trình, ghép phách và xuất kết quả (FR-001..FR-012, FR-017, FR-019..FR-021).
- Giám khảo (GRADER): chỉ truy cập bài được phân công theo phách và PDF ẩn danh; nhập điểm/ghi chú, xác nhận lượt chấm và báo vấn đề; không xem danh tính/bản gốc hay điểm người khác trước khi hoàn thành (FR-013..FR-015, FR-023).
- Hội đồng chấm thi (COUNCIL): xem dữ liệu REVIEW_REQUIRED không có danh tính và quyết định điểm khi bất đồng chuyên môn, bắt buộc lý do (FR-017, FR-018).
- API Gateway và bốn service nghiệp vụ là actors hệ thống; RabbitMQ và Object Storage là hạ tầng tích hợp. Mỗi service tự authorization theo resource dù Gateway đã xác thực (FR-023).

## Ranh giới dữ liệu và bảo mật cốt lõi
Identity/Pseudonym Service là chủ sở hữu duy nhất của roster và ánh xạ candidate–pseudonym–submission; service khác không đọc Identity DB trực tiếp (BR-006). Submission Service sở hữu metadata xử lý và tham chiếu object; Exam & Grading Service sở hữu cấu hình/chấm; Result/Review Service sở hữu review và kết quả. GRADER chỉ nhận tài nguyên ẩn danh (BR-007). Bản ORIGINAL không bị sửa trong quá trình ẩn danh (BR-013).

## Vòng đời cấp cao
Upload không chờ anonymization (FR-006). Trạng thái chuẩn là UPLOADING → UPLOADED → VALIDATING → ANONYMIZING → READY_FOR_GRADING → GRADING → COMPLETED → FINALIZED; REVIEW_REQUIRED là nhánh nghiệp vụ và FAILED dành cho file hỏng hoặc lỗi kỹ thuật hết retry (FR-022, BR-015, BR-016). Sau FINALIZED, retention_deadline = finalized_at + 1 năm và hệ thống purge dữ liệu chi tiết nhưng giữ thông tin thí sinh, kỳ thi/môn thi và điểm cuối (FR-025).

## Tiêu chí thành công kiến trúc
Kiến trúc phải bảo đảm không có đường truy cập dành cho GRADER tới ORIGINAL/danh tính; xử lý PDF không khóa request upload; trạng thái có SSE và snapshot; chấm chéo áp dụng so sánh từng cặp và round-half-up 2 chữ số; mỗi service có DB riêng; hệ thống có thể chạy toàn bộ bằng Docker Compose. Đây là các tiêu chí truy vết trực tiếp tới FR-006, FR-009, FR-016, FR-023 và các NFR security/scalability/availability.