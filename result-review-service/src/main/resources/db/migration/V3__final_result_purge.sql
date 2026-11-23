-- §9.3: sau khi purge thành công, FinalResult.submission_id được đặt NULL
-- (liên kết tới dữ liệu chi tiết đã xóa); chỉ còn dữ liệu lõi.
ALTER TABLE final_result ALTER COLUMN submission_id DROP NOT NULL;
