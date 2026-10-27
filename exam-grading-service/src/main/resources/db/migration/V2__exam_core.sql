CREATE TABLE exam (
    exam_id uuid PRIMARY KEY, exam_code varchar(40) NOT NULL UNIQUE, name varchar(255) NOT NULL,
    subject varchar(255) NOT NULL, exam_time timestamptz, grade_level varchar(120), scale numeric(6,2) NOT NULL DEFAULT 10,
    cross_grading_enabled boolean NOT NULL DEFAULT false, grader_count integer NOT NULL DEFAULT 1,
    difference_threshold numeric(6,2) NOT NULL DEFAULT 0, status varchar(24) NOT NULL DEFAULT 'DRAFT',
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE exam_question (question_id uuid PRIMARY KEY, exam_id uuid NOT NULL REFERENCES exam(exam_id) ON DELETE CASCADE, order_no integer NOT NULL, content text NOT NULL, max_score numeric(6,2) NOT NULL, required boolean NOT NULL DEFAULT true, UNIQUE(exam_id, order_no));
CREATE TABLE exam_grader (exam_id uuid NOT NULL REFERENCES exam(exam_id) ON DELETE CASCADE, user_id uuid NOT NULL, PRIMARY KEY(exam_id, user_id));
CREATE TABLE grading_assignment (assignment_id uuid PRIMARY KEY, exam_id uuid NOT NULL REFERENCES exam(exam_id), submission_id uuid NOT NULL, grader_id uuid NOT NULL, status varchar(24) NOT NULL DEFAULT 'ASSIGNED', assigned_at timestamptz NOT NULL DEFAULT now(), UNIQUE(submission_id, grader_id));
CREATE TABLE grading_attempt (attempt_id uuid PRIMARY KEY, assignment_id uuid NOT NULL REFERENCES grading_assignment(assignment_id), total_score numeric(6,2), status varchar(24) NOT NULL DEFAULT 'DRAFT', confirmed_at timestamptz);
CREATE TABLE question_score (attempt_id uuid NOT NULL REFERENCES grading_attempt(attempt_id) ON DELETE CASCADE, question_id uuid NOT NULL, score numeric(6,2) NOT NULL, note text, needs_review boolean NOT NULL DEFAULT false, PRIMARY KEY(attempt_id, question_id));
