ALTER TABLE paper_template
    ADD COLUMN description varchar(500),
    ADD COLUMN source_pdf bytea,
    ADD COLUMN source_file_name varchar(255);
