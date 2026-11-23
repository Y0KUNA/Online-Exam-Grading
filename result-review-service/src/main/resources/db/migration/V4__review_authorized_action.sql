-- §5/§3.2 review decision flow: cột còn thiếu so với spec —
--   authorized_action : hành động Hội đồng đã cho phép khi status=ACTION_AUTHORIZED
--                       (FORCE_ANONYMIZE|REPROCESS|REPLACE_FILE|REGRADE|SET_FINAL_SCORE|DISMISS)
--   resolution_type   : cách Review đóng (decisionType hoặc WITHDRAWN)
--   created_by        : actor tạo Review (worker/EO); InternalReviewController đã
--                       insert cột này từ trước nên bổ sung luôn.
ALTER TABLE review
    ADD COLUMN IF NOT EXISTS authorized_action varchar(20),
    ADD COLUMN IF NOT EXISTS resolution_type varchar(32),
    ADD COLUMN IF NOT EXISTS created_by uuid;
