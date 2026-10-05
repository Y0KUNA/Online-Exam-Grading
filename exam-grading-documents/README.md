# Bộ tài liệu cho Coding Agent: Hệ thống số hóa và quản lý chấm bài thi tự luận

## Đọc theo thứ tự

| # | File | Vai trò |
|---|---|---|
| 1 | `README.md` | Mục lục này |
| 2 | `12-agent-work-plan.md` | **Điểm bắt đầu**: prompt giao việc, work packages S0–WP9, ma trận phân quyền |
| 3 | `10-implementation-spec.md` | Addendum: quyết định bổ sung, errata, máy trạng thái, API bổ sung, bảo mật, schema JSON, RabbitMQ, quy tắc nghiệp vụ |
| 4 | `11-acceptance-tests.md` | Tiêu chí nghiệm thu (Given/When/Then, bảng biên chấm chéo) |
| 5 | `13-user-table.md` + `sql/identity/` | Bảng `users`/`graders`, đăng nhập, API người dùng, migration |
| 6 | `source/requirements-ver1_2.json` | Nguồn nghiệp vụ (FR/BR/NFR). Đã chuyển thành JSON hợp lệ |
| 7 | `source/01…09` | Thiết kế gốc: overview, architecture, components, data model, API contracts, NFR, tech stack, ADR, risks |

## Thứ tự ưu tiên khi mâu thuẫn
`requirements` > `10-implementation-spec` (Errata §2) > `source/02…09`.

## Quyết định đã chốt bởi chủ dự án
- **Hội đồng quyết định mọi loại review** (`ANONYMIZATION`, `GRADER_FLAG`, `SCORE_DIFFERENCE`, `REMARK`). "Bất đồng chuyên môn" = các giám khảo chấm lệch nhau, Hội đồng thống nhất điểm. Chỉ **một người đại diện** thao tác; Phòng khảo thí tạo yêu cầu phúc khảo và **thực thi** hành động kỹ thuật Hội đồng cho phép (retry, thay file, giao giám khảo chấm lại) vì Hội đồng không được thấy bản gốc (file 10 §3.2).
- **Phúc khảo sau FINALIZED** được phép; **hạn retention tính từ lần FINALIZED đầu và không đổi** (file 10 §9.5, `REMARK_WINDOW_DAYS` mặc định 0 = đến hết retention).
- **Xác nhận gán roster** thực hiện ở giao diện (D-22).
- **Phòng khảo thí mở lại vấn đề ẩn danh** khi bài đã `READY_FOR_GRADING` (`anonymization-issues`, file 10 §4.6).
- **Xem trước vùng che**: `anonymization-preview` (trên bài đã có) và `paper-templates/preview` (bằng bài mẫu, dùng khi kỳ thi còn DRAFT) (file 10 §4.5).
- **Thay file scan** (`replace-file`, §4.4), **người dùng** (`users` + `graders`, file 13), **RabbitMQ có queue `validate`** (§8).

## Các vấn đề cần chốt để quyết định
Bảng dưới đây là danh sách các điểm còn lại mà chủ dự án cần xác nhận trước khi bắt tay vào triển khai chính thức. Nếu không chốt trong vòng ngắn, agent sẽ dùng giá trị mặc định ghi trong file 10 §1.2 và ghi vào docs/ASSUMPTIONS.md.

| Mã | Vấn đề cần quyết định | Giá trị mặc định đã ghi | Ghi chú |
|---|---|---|---|
| Q-04 | `needsReview=true` có tự chuyển trạng thái REVIEW_REQUIRED không? | Không. Chỉ `POST /submissions/{id}/reviews` mới tạo Review | Dùng để tránh chuyển trạng thái sai khi chỉ hiển thị dấu hiệu |
| Q-05 | Khi GRADER_FLAG mở Review, đánh giá các lượt chấm đang dở của giám khảo khác | Giữ nguyên DRAFT, bỏ qua đánh giá khi còn Review chưa đóng | Important cho logic chấm chéo |
| Q-06 | Bulk assignment khi tắt chấm chéo nhưng truyền nhiều `graderIds` | Round-robin, mỗi bài 1 giám khảo | Hợp với spec đã ghi |
| Q-07 | Đăng nhập với hệ thống local hay IdP ngoài | Giữ `AUTH_LOCAL_ENABLED=true` | Nếu switch sau này thì sửa kịch bản auth |
| Q-08 | Upload bài chỉ được phép khi exam ACTIVE | Chỉ khi `Exam.status=ACTIVE` | Có hiệu lực cho upload và bulk assignment |
| Q-09 | Có phép sửa template đã có submission | Không. Chỉ tạo version mới | Bảo toàn tính lịch sử |
| Q-10 | Export có được khi chưa chốt hết không | Không cho export nếu còn bài chưa `FINALIZED`; khi phát hiện tồn tại bài chưa finalized, trả cảnh báo và chặn export | `RESULTS_NOT_FINALIZED`/`EXPORT_BLOCKED_UNFINALIZED` với cảnh báo danh sách số lượng chưa finalized |
| Q-11 | Thay file scan đã được quyết định hay chưa | Đã chốt: có endpoint `replace-file` | Trạng thái không đổi nhóm ngữ cảnh |
| Q-12 | JWT TTL và refresh token | Access token 15 phút, chưa có refresh token trong MVP | Không thêm refresh nếu chưa quyết định |
| Q-13 | Thay file khi đã có lượt chấm CONFIRMED | 409 `ATTEMPTS_EXIST` | Hội đồng chỉ dùng `DISMISS`/`REGRADE` thay vì thay file |
| Q-14 | Giới hạn phúc khảo sau FINALIZED | `REMARK_WINDOW_DAYS=0` mặc định = đến hết retention | Nếu muốn khóa cứng, set 30 |
| Q-15 | Khi retry reprocess với template mới | Cho phép `templateId`, thay đổi `Submission.template_id` | Không đổi `Exam.template_id` |

## Mức sẵn sàng để sinh agent
Tài liệu hiện tại đã đủ để bắt đầu build trong phạm vi backend, nhưng cần có một lần xác nhận chủ dự án cho các mục trên trước khi đóng gói release hoặc đánh giá production. Với các mục không xác nhận, dùng giá trị mặc định ghi trong file 10 §1.2 và ghi rõ vào docs/ASSUMPTIONS.md.

## Hai điểm kỹ thuật rủi ro cao
1. **Ẩn danh bằng cách phủ hình chữ nhật không an toàn** với PDF scan (ảnh gốc vẫn còn trong file). Đặc tả yêu cầu rasterize trang 1 (file 10 §7, test AT-C03).
2. **Presigned URL qua Gateway** có thể sai chữ ký do header `Host`/path prefix. Cần spike S0 trước khi làm upload (file 10 §6.3).

## Phạm vi
Chỉ backend. Không gồm Web Client.
