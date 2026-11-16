# Test Upload Complete Flow (TICKET 06)

Hướng dẫn test end-to-end cho flow upload bài thi: init → upload MinIO → complete → verify.

## Prerequisites

- Docker đang chạy (MinIO + PostgreSQL)
- submission-service đang chạy trên host port 8082
- `.env` file đã cấu hình đúng

## Steps

### 1. Chạy hạ tầng

```powershell
cd D:\DATN\Online-Exam-Grading
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.dev.yml --env-file .env up -d
```

Kiểm tra:
```powershell
docker ps
# MinIO: deploy-minio-1 (ports 9000, 9001)
# PostgreSQL: deploy-postgres-submission-1 (port 5433)
```

### 2. Chạy submission-service

```powershell
cd D:\DATN\Online-Exam-Grading
.\gradlew.bat :submission-service:bootRun
```

### 3. Tạo file PDF test

```powershell
echo "dummy pdf content" > test.pdf
```

### 4. Init submission

```powershell
curl -X POST http://localhost:8082/api/v1/exams/00000000-0000-0000-0000-000000000001/submissions `
  -H "Content-Type: application/json" `
  -d '{"candidateId": "00000000-0000-0000-0000-000000000004", "templateId": "00000000-0000-0000-0000-000000000001", "fileName": "test.pdf", "fileSizeBytes": 18, "contentType": "application/json"}'
```

Lưu kết quả (response JSON):
- `submissionId`: UUID của submission
- `upload.url`: `http://localhost:8080/storage/original`
- `upload.fields`: object chứa các presigned form fields:
  - `key`: object key trong MinIO
  - `x-amz-date`
  - `x-amz-signature`
  - `x-amz-algorithm`
  - `x-amz-credential`
  - `policy`

Nếu trả về 201, submission đã được tạo với status `UPLOADING`.

### 5. Upload file vào MinIO

**Lưu ý**: Trong môi trường dev, có thể upload trực tiếp vào MinIO (bypass Gateway) bằng cách dùng presigned POST từ step 4.

```powershell
# Thay thế các giá trị placeholder bằng giá trị thực từ upload.fields
curl -X POST http://localhost:9000/original `
  -F "key=<object_key từ init>" `
  -F "Content-Type=application/pdf" `
  -F "x-amz-date=<date từ init>" `
  -F "x-amz-signature=<signature từ init>" `
  -F "x-amz-algorithm=<algorithm từ init>" `
  -F "x-amz-credential=<credential từ init>" `
  -F "policy=<policy từ init>" `
  -F "file=@test.pdf"
```

Expected: `204 No Content`

Nếu upload thành công, file sẽ nằm trong MinIO bucket `original` với key đã định.

### 6. Complete submission

```powershell
curl -X POST http://localhost:8082/api/v1/submissions/<submissionId>/complete `
  -H "Content-Type: application/json"
```

Expected response:
```json
{
  "submissionId": "<uuid>",
  "status": "UPLOADED"
}
```

Nếu trả về 200, submission đã chuyển sang status `UPLOADED` và các side effects đã được thực hiện:
- `exam_file` row được tạo
- `processing_job` row được tạo với status `QUEUED`
- `submission_event` row được append

### 7. Verify Database

#### Check submission status

```powershell
docker exec deploy-postgres-submission-1 psql -U exam_grading -d submission_db -c "SELECT submission_id, status, object_key FROM submission WHERE submission_id = '<submissionId>';"
```

Expected: `status = UPLOADED`, `object_key` không null

#### Check exam_file

```powershell
docker exec deploy-postgres-submission-1 psql -U exam_grading -d submission_db -c "SELECT file_id, type, size_bytes, object_key FROM exam_file WHERE submission_id = '<submissionId>';"
```

Expected:
- `type = ORIGINAL`
- `size_bytes = 18` (hoặc size thực tế của file)
- `object_key` trùng với object key từ init

#### Check processing_job

```powershell
docker exec deploy-postgres-submission-1 psql -U exam_grading -d submission_db -c "SELECT job_id, step, status FROM processing_job WHERE submission_id = '<submissionId>';"
```

Expected:
- `step = VALIDATE`
- `status = QUEUED`

#### Check submission_event

```powershell
docker exec deploy-postgres-submission-1 psql -U exam_grading -d submission_db -c "SELECT status, progress FROM submission_event WHERE submission_id = '<submissionId>' ORDER BY created_at DESC LIMIT 2;"
```

Expected: 2 events (UPLOADING → UPLOADED)

### 8. Verify MinIO

```powershell
# Cần setup mc alias trước
docker exec deploy-minio-1 mc alias set local http://localhost:9000 minioadmin change-me

# Kiểm tra file
docker exec deploy-minio-1 mc stat local/original/<object_key>
```

Expected output:
```
Name      : v1.pdf
Date      : 2026-11-16 ... UTC
Size      : 18 B
ETag      : ...
Type      : file
Metadata  :
  Content-Type: application/pdf
```

### 9. Test Idempotency

Gọi complete lần thứ 2:

```powershell
curl -X POST http://localhost:8082/api/v1/submissions/<submissionId>/complete `
  -H "Content-Type: application/json"
```

Expected: Vẫn trả về 200 với status `UPLOADED`, nhưng không tạo duplicate rows trong `exam_file` hoặc `processing_job`.

### 10. Test Error Cases

#### Upload expired

Đợi cho `upload_expires_at` hết hạn (default 10 phút), sau đó gọi complete.

Expected: `409` với code `UPLOAD_EXPIRED`

#### File not uploaded

Gọi complete mà không upload file vào MinIO.

Expected: `409` với code `UPLOAD_NOT_FOUND`

#### File too large

Tạo file > 50MB (hoặc > declared size + tolerance), upload, sau đó complete.

Expected: `422` với code `FILE_TOO_LARGE`, file bị xóa khỏi MinIO

## Troubleshooting

### Submission service không kết nối được MinIO

Kiểm tra `application.yml`:
```yaml
app.storage:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: change-me
  bucket: original
```

### Complete trả về 500 INTERNAL_ERROR

Kiểm tra log submission service (nếu chạy trên host, log sẽ xuất ra console). Các lỗi thường gặp:
- SQL column name sai → check `JdbcExamFileRepository`
- Storage connection failed → check MinIO credentials

### File không upload được vào MinIO

Kiểm tra presigned POST fields có đúng không. Trong môi trường dev, có thể test bằng cách upload trực tiếp vào MinIO như step 5.

## Cleanup

```powershell
# Stop services
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.dev.yml down

# Xóa test file
Remove-Item test.pdf
```
