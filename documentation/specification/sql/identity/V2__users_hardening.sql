-- V2: điều chỉnh đề xuất (xem 13-user-table.md §3). Chưa được chạy thử: cần test bằng Testcontainers PostgreSQL 16.
-- Lưu ý: nếu bảng graders đã có dữ liệu mà user_id trỏ tới user không phải GRADER hoặc không tồn tại,
-- bước thêm FK sẽ lỗi; dọn dữ liệu trước.

-- 1) Đảm bảo hồ sơ giám khảo chỉ gắn với user có role = 'GRADER'
ALTER TABLE users
    ADD CONSTRAINT uq_users_user_id_role UNIQUE (user_id, role);

ALTER TABLE graders
    ADD COLUMN role VARCHAR(30) NOT NULL DEFAULT 'GRADER'
        CONSTRAINT ck_graders_role CHECK (role = 'GRADER');

ALTER TABLE graders
    ADD CONSTRAINT fk_graders_user
        FOREIGN KEY (user_id, role) REFERENCES users (user_id, role)
        ON DELETE RESTRICT;

-- 2) Username / email không phân biệt hoa thường
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email)) WHERE email IS NOT NULL;

-- 3) Khóa tài khoản và buộc đổi mật khẩu
ALTER TABLE users
    ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- 4) Tự cập nhật updated_at
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at   BEFORE UPDATE ON users   FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_graders_updated_at BEFORE UPDATE ON graders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 5) Index phục vụ danh sách lọc
CREATE INDEX idx_users_role_active ON users (role, is_active);
