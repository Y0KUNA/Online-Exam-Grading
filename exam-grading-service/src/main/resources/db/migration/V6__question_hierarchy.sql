ALTER TABLE exam_question ADD COLUMN parent_question_id uuid REFERENCES exam_question(question_id) ON DELETE CASCADE;
ALTER TABLE exam_question ADD COLUMN item_code varchar(40);

UPDATE exam_question SET item_code = 'C' || order_no WHERE item_code IS NULL;

ALTER TABLE exam_question ALTER COLUMN item_code SET NOT NULL;
ALTER TABLE exam_question ADD CONSTRAINT uq_exam_question_item_code UNIQUE (exam_id, item_code);
CREATE INDEX ix_exam_question_parent ON exam_question(parent_question_id);
