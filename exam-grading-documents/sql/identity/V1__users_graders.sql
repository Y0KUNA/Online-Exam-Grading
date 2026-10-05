-- V1: schema do chủ dự án tạo (giữ nguyên).
-- Nếu hai bảng đã được tạo thủ công trong DB: dùng `flyway baseline -baselineVersion=1` thay vì chạy file này.
-- gen_random_uuid() có sẵn từ PostgreSQL 13 (dự án dùng 16).

CREATE TABLE users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,

    full_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE,

    role VARCHAR(30) NOT NULL
        CHECK (role IN (
            'EXAM_OFFICE',
            'GRADER',
            'COUNCIL'
        )),

    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    last_login_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE graders (
    grader_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID NOT NULL UNIQUE,

    employee_code VARCHAR(50) UNIQUE,
    phone VARCHAR(20),
    department VARCHAR(150),
    academic_title VARCHAR(100),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
