# Online Exam Grading — Backend

Backend monorepo for the essay-exam digitization and grading system. The design follows `exam-grading-spec/10-implementation-spec.md`: Java 21, Spring Boot, Gradle multi-module, database-per-service, RabbitMQ only for the PDF processing pipeline, PostgreSQL and MinIO.

## Modules

- `api-gateway`: public entry point; JWT validation and `/storage/**` pass-through only.
- `identity-service`: users, graders, roster and candidate↔submission↔pseudonym mapping.
- `submission-service`: upload metadata, PDF processing, templates, state machine and object storage.
- `exam-grading-service`: exams, rubrics, assignments and grading attempts.
- `result-review-service`: reviews, finalization, export and retention orchestration.
- `common`: cross-cutting error, pagination, audit, idempotency and signed-internal-request primitives. It must not contain domain ownership.

Each service owns its migration directory and database; never use another service's database credentials.

## Start

1. Copy `.env.example` to `.env` and replace every secret.
2. Start local infrastructure with `docker compose -f deploy/docker-compose.yml up -d`.
3. Build all modules with `gradle build` (Java 21 required). Commit the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) once Gradle is available on the development machine.

The modules are intentionally a production-oriented scaffold: API and domain packages are present, but business endpoints are added work-package by work-package from `exam-grading-spec`.
