-- TICKET 12 (task 3): processing_job columns for reprocess (spec §5/§8, T17/T18/T27)
-- Worker đọc overwrite/templateId từ ProcessingJob theo jobId, không tin message.
--   file_version : version của file lúc job được tạo (chống message cũ, §8)
--   force        : bỏ qua kiểm tra lệch kích thước ±5% (job validate sau FORCE_ANONYMIZE, T17)
--   overwrite    : job anonymize reprocess luôn dựng lại và ghi đè object (§8)
--   template_id  : template dùng cho lần chạy (mặc định Submission.template_id; T18 reprocess có thể đổi)

ALTER TABLE processing_job
    ADD COLUMN IF NOT EXISTS file_version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS force boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS overwrite boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS template_id uuid;
