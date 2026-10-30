# Graph Report - Online-Exam-Grading  (2026-10-30)

## Corpus Check
- 0 files · ~0 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 857 nodes · 1279 edges · 91 communities (41 shown, 50 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 73 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- CorrelationIdFilter
- SecurityConfig.java
- App.jsx
- CursorCodec
- IdentityExceptionHandler.java
- package.json
- InternalSubmissionController.java
- interval
- app.auth
- api-gateway/src/main/resources/application.yml
- UserAccount
- environment
- org.springframework.boot.autoconfigure.SpringBootApplication
- OutboxMessage
- HmacSigner
- .filter
- RedactingAuditLogger.java
- RabbitMqConfig
- GatewaySecurityConfig.java
- ExamService
- IdempotencyRecord
- ReviewController
- spring
- LoginRateLimitFilter
- SubmissionStatus
- uuid
- services
- StorageRateLimitFilter
- spring
- spring
- FoundationPrimitivesTest.java
- environment
- postgres-identity
- CandidateController.java
- spring
- args
- minio
- datasource
- datasource
- datasource
- volumes
- submission-service
- .oxlintrc.json
- healthcheck
- ExamStatus
- ReviewStatus
- services
- Headers
- depends on
- environment
- include
- Submission Service
- DocxConfig
- JwtConfig
- RetentionConfig
- Anonymization Workflow
- Operational Open Items
- SubmissionClient.java
- ExamRepositoryMarker.java
- GradingServiceMarker.java
- UserRepositoryMarker.java
- IdentityServiceMarker.java
- ExamGradingClient.java
- RetentionScheduler.java
- ReviewRepositoryMarker.java
- ResultReviewServiceMarker.java
- UploadCleanupJob.java
- ProcessingJobConsumer.java
- SubmissionRepositoryMarker.java
- SubmissionServiceMarker.java
- ObjectStorageGateway.java
- Exam and Grading Service
- Vite Favicon
- Social and Documentation Icon Sprite
- Decorative Data Layers
- React Logo Asset
- Vite Logo

## God Nodes (most connected - your core abstractions)
1. `UserAccount` - 26 edges
2. `IdentityAuthService` - 22 edges
3. `ExamService` - 19 edges
4. `UserRole` - 16 edges
5. `environment` - 16 edges
6. `App()` - 15 edges
7. `JdbcExamRepository` - 14 edges
8. `SubmissionStatus` - 13 edges
9. `services` - 13 edges
10. `app.auth` - 12 edges

## Surprising Connections (you probably didn't know these)
- `StorageAccessFilterTest` --references--> `StorageAccessFilter`  [EXTRACTED]
  api-gateway/src/test/java/vn/edu/examgrading/gateway/filter/StorageAccessFilterTest.java → api-gateway/src/main/java/vn/edu/examgrading/gateway/filter/StorageAccessFilter.java
- `UserAccount` --references--> `UserRole`  [EXTRACTED]
  identity-service/src/main/java/vn/edu/examgrading/identity/domain/UserAccount.java → identity-service/src/main/java/vn/edu/examgrading/identity/domain/UserRole.java
- `UserAccountRepository` --references--> `UserAccount`  [EXTRACTED]
  identity-service/src/main/java/vn/edu/examgrading/identity/repository/UserAccountRepository.java → identity-service/src/main/java/vn/edu/examgrading/identity/domain/UserAccount.java
- `InMemoryOutbox` --references--> `OutboxMessage`  [EXTRACTED]
  common/src/main/java/vn/edu/examgrading/common/outbox/InMemoryOutbox.java → common/src/main/java/vn/edu/examgrading/common/outbox/OutboxMessage.java
- `InMemoryOutbox` --implements--> `OutboxPublisher`  [EXTRACTED]
  common/src/main/java/vn/edu/examgrading/common/outbox/InMemoryOutbox.java → common/src/main/java/vn/edu/examgrading/common/outbox/OutboxPublisher.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Reliability and Recovery Controls** — documentation_specification_source_09_risks_open_items_final_direct_upload_recovery, documentation_specification_source_09_risks_open_items_final_grading_reconciliation, documentation_specification_source_09_risks_open_items_final_retention_saga [EXTRACTED 1.00]

## Communities (91 total, 50 thin omitted)

### Community 0 - "CorrelationIdFilter"
Cohesion: 0.15
Nodes (4): CorrelationIdFilter, InternalPathNotFoundFilter, CorrelationIdFilterTest, InternalPathNotFoundFilterTest

### Community 2 - "App.jsx"
Cohesion: 0.13
Nodes (33): api(), API_BASE_URL, ApiError, gradingApi, upload(), accessTypes, App(), Button() (+25 more)

### Community 3 - "CursorCodec"
Cohesion: 0.20
Nodes (3): CursorCodec, CursorCodecTest, HmacSignerTest

### Community 4 - "IdentityExceptionHandler.java"
Cohesion: 0.18
Nodes (4): ApiError, GlobalExceptionHandler, ApiError, IdentityExceptionHandler

### Community 5 - "package.json"
Cohesion: 0.09
Nodes (26): Frontend Demo HTML Entry Point, dependencies, react@^19.2.8, react-dom@^19.2.8, devDependencies, oxlint@^1.81.0, @types/react@^19.2.18, @types/react-dom@^19.2.7 (+18 more)

### Community 7 - "interval"
Cohesion: 0.14
Nodes (24): healthcheck, ports, postgres-exam-grading, depends_on, healthcheck, interval, retries, test (+16 more)

### Community 8 - "app.auth"
Cohesion: 0.09
Nodes (22): app.auth, bootstrap-council-name, bootstrap-council-password, bootstrap-council-username, bootstrap-office-name, bootstrap-password, bootstrap-username, issuer (+14 more)

### Community 9 - "api-gateway/src/main/resources/application.yml"
Cohesion: 0.09
Nodes (21): app.login.rate-limit-per-minute, app.storage, max-pdf-bytes, rate-limit-per-minute, upstream-host, gateway, globalcors, routes (+13 more)

### Community 10 - "UserAccount"
Cohesion: 0.05
Nodes (19): BootstrapAdmin, AuthController, UserAccount, UserRole, COUNCIL, EXAM_OFFICE, GRADER, ChangePasswordRequest (+11 more)

### Community 11 - "environment"
Cohesion: 0.13
Nodes (20): AUTH_LOCAL_ENABLED, BOOTSTRAP_ADMIN_PASSWORD, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_COUNCIL_NAME, BOOTSTRAP_COUNCIL_PASSWORD, BOOTSTRAP_COUNCIL_USERNAME, BOOTSTRAP_OFFICE_NAME, DB_PASSWORD (+12 more)

### Community 12 - "org.springframework.boot.autoconfigure.SpringBootApplication"
Cohesion: 0.12
Nodes (5): ApiGatewayApplication, ExamGradingServiceApplication, IdentityServiceApplication, ResultReviewServiceApplication, SubmissionServiceApplication

### Community 13 - "OutboxMessage"
Cohesion: 0.17
Nodes (5): IdempotentConsumer, InMemoryOutbox, OutboxMessage, OutboxPublisher, OutboxRelay

### Community 14 - "HmacSigner"
Cohesion: 0.18
Nodes (5): HmacSigner, ServiceRequestSigner, SignedServiceRequest, SignedUserContext, FoundationPrimitivesTest

### Community 16 - "RedactingAuditLogger.java"
Cohesion: 0.21
Nodes (4): AuditEntry, AuditLogger, RedactingAuditLogger, SensitiveDataRedactor

### Community 20 - "ExamService"
Cohesion: 0.06
Nodes (7): CursorPage, ExamController, CreateExamRequest, JdbcExamRepository, ExamService, ExamServiceTest, CouncilDecisionRequest

### Community 21 - "IdempotencyRecord"
Cohesion: 0.36
Nodes (4): IdempotencyExecutor, IdempotencyRecord, IdempotencyStore, InMemoryIdempotencyStore

### Community 23 - "spring"
Cohesion: 0.15
Nodes (12): app.internal.hmac-secret, password, url, username, management.endpoints.web.exposure.include, server.port, spring, application.name (+4 more)

### Community 25 - "SubmissionStatus"
Cohesion: 0.13
Nodes (12): InternalSubmissionController, SubmissionStatus, ANONYMIZING, COMPLETED, FAILED, FINALIZED, GRADING, READY_FOR_GRADING (+4 more)

### Community 27 - "services"
Cohesion: 0.20
Nodes (10): minio, S3_ACCESS_KEY, S3_SECRET_KEY, condition, depends_on, entrypoint, environment, image (+2 more)

### Community 29 - "spring"
Cohesion: 0.18
Nodes (10): password, url, username, management.endpoints.web.exposure.include, server.port, spring, application.name, datasource (+2 more)

### Community 30 - "spring"
Cohesion: 0.18
Nodes (10): password, url, username, management.endpoints.web.exposure.include, server.port, spring, application.name, datasource (+2 more)

### Community 32 - "FoundationPrimitivesTest.java"
Cohesion: 0.16
Nodes (4): InMemoryReplayGuard, InternalRequestVerifier, ReplayGuard, ServiceRequestVerifier

### Community 33 - "environment"
Cohesion: 0.20
Nodes (10): environment, EXAM_GRADING_URL, IDENTITY_URL, JWT_JWKS_URI, MAX_PDF_BYTES, RESULT_REVIEW_URL, S3_INTERNAL_ENDPOINT, S3_UPSTREAM_HOST (+2 more)

### Community 34 - "postgres-identity"
Cohesion: 0.57
Nodes (7): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, environment, environment, environment, environment

### Community 36 - "CandidateController.java"
Cohesion: 0.07
Nodes (4): RequestLoggingFilter, RequestLoggingFilter, CandidateController, CsvRecord

### Community 37 - "spring"
Cohesion: 0.20
Nodes (9): password, url, username, enabled, addresses, spring, datasource, flyway (+1 more)

### Community 38 - "args"
Cohesion: 0.19
Nodes (16): build, SERVICE, args, context, dockerfile, postgres-identity, build, build (+8 more)

### Community 40 - "minio"
Cohesion: 0.25
Nodes (8): MINIO_ROOT_PASSWORD, MINIO_ROOT_USER, command, environment, healthcheck, image, volumes, minio

### Community 41 - "datasource"
Cohesion: 0.25
Nodes (7): password, url, username, enabled, spring, datasource, flyway

### Community 43 - "datasource"
Cohesion: 0.25
Nodes (7): password, url, username, enabled, spring, datasource, flyway

### Community 44 - "datasource"
Cohesion: 0.25
Nodes (7): password, url, username, enabled, spring, datasource, flyway

### Community 46 - "volumes"
Cohesion: 0.29
Nodes (6): volumes, exam-grading-data, identity-data, minio-data, result-review-data, submission-data

### Community 47 - "submission-service"
Cohesion: 0.40
Nodes (5): postgres-submission, rabbitmq, condition, condition, depends_on

### Community 50 - ".oxlintrc.json"
Cohesion: 0.33
Nodes (5): plugins, rules, react/only-export-components, react/rules-of-hooks, $schema

### Community 52 - "healthcheck"
Cohesion: 0.40
Nodes (5): postgres-result-review, condition, depends_on, healthcheck, result-review-service

### Community 53 - "ExamStatus"
Cohesion: 0.40
Nodes (4): ExamStatus, ACTIVE, CLOSED, DRAFT

### Community 55 - "ReviewStatus"
Cohesion: 0.40
Nodes (4): ReviewStatus, ACTION_AUTHORIZED, OPEN, RESOLVED

### Community 56 - "services"
Cohesion: 0.25
Nodes (8): image, volumes, healthcheck, image, volumes, services, postgres-identity, postgres-submission

### Community 60 - "depends on"
Cohesion: 0.67
Nodes (3): depends_on, minio-init, condition

### Community 61 - "environment"
Cohesion: 0.67
Nodes (3): RABBITMQ_DEFAULT_PASS, RABBITMQ_DEFAULT_USER, environment

### Community 63 - "Submission Service"
Cohesion: 0.67
Nodes (3): Submission State Machine, Four-Service Microservice Architecture, Submission Service

## Knowledge Gaps
- **31 isolated node(s):** `COUNCIL`, `EXAM_OFFICE`, `GRADER`, `ANONYMIZING`, `COMPLETED` (+26 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 358 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **50 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `IdentityAuthService` connect `UserAccount` to `IdentityExceptionHandler.java`, `InternalSubmissionController.java`?**
  _High betweenness centrality (0.024) - this node is a cross-community bridge._
- **What connects `COUNCIL`, `EXAM_OFFICE`, `GRADER` to the rest of the system?**
  _31 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SecurityConfig.java` be split into smaller, more focused modules?**
  _Cohesion score 0.08333333333333333 - nodes in this community are weakly interconnected._
- **Why does `UserAccount` connect `UserAccount` to `uuid`?**
  _High betweenness centrality (0.016) - this node is a cross-community bridge._
- **Should `App.jsx` be split into smaller, more focused modules?**
  _Cohesion score 0.12660028449502134 - nodes in this community are weakly interconnected._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.08735632183908046 - nodes in this community are weakly interconnected._
- **Should `InternalSubmissionController.java` be split into smaller, more focused modules?**
  _Cohesion score 0.1323529411764706 - nodes in this community are weakly interconnected._