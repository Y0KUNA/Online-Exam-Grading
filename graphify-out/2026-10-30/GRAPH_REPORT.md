# Graph Report - Online-Exam-Grading  (2026-10-30)

## Corpus Check
- 168 files · ~59,767 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 10 file(s) not represented in the graph (top: (none) 3, .css 3, .example 2)

## Summary
- 956 nodes · 1689 edges · 101 communities (43 shown, 58 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 73 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- org.springframework.web.server.ServerWebExchange
- SecurityConfig.java
- App.jsx
- org.junit.jupiter.api.Test
- IdentityExceptionHandler.java
- package.json
- map
- interval
- app.auth
- api-gateway/src/main/resources/application.yml
- UserAccount
- environment
- org.springframework.boot.autoconfigure.SpringBootApplication
- OutboxMessage
- HmacSigner
- FoundationPrimitivesTest.java
- RedactingAuditLogger.java
- IdentityAuthService
- IdentityAuthService.java
- AuthController.java
- ExamServiceTest.java
- IdempotencyRecord
- ReviewController
- spring
- SubmissionStatus
- uuid
- services
- ExamService
- spring
- spring
- ReplayGuard
- environment
- postgres-identity
- JdbcExamRepository
- CandidateController
- spring
- args
- UserCredentialsResponse
- minio
- datasource
- UserAccountRepository
- datasource
- datasource
- volumes
- submission-service
- ExamController
- InternalSubmissionController
- .oxlintrc.json
- healthcheck
- healthcheck
- ExamStatus
- .login()
- ReviewStatus
- healthcheck
- notblank
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
1. `UserAccount` - 28 edges
2. `IdentityAuthService` - 26 edges
3. `ExamService` - 20 edges
4. `environment` - 16 edges
5. `JdbcExamRepository` - 16 edges
6. `UserRole` - 16 edges
7. `App()` - 15 edges
8. `CandidateController` - 15 edges
9. `StorageAccessFilter` - 13 edges
10. `services` - 13 edges

## Surprising Connections (you probably didn't know these)
- `StorageAccessFilterTest` --references--> `StorageAccessFilter`  [EXTRACTED]
  api-gateway/src/test/java/vn/edu/examgrading/gateway/filter/StorageAccessFilterTest.java → api-gateway/src/main/java/vn/edu/examgrading/gateway/filter/StorageAccessFilter.java
- `ExamController` --references--> `ExamService`  [EXTRACTED]
  exam-grading-service/src/main/java/vn/edu/examgrading/examgrading/controller/ExamController.java → exam-grading-service/src/main/java/vn/edu/examgrading/examgrading/service/ExamService.java
- `ExamService` --references--> `JdbcExamRepository`  [EXTRACTED]
  exam-grading-service/src/main/java/vn/edu/examgrading/examgrading/service/ExamService.java → exam-grading-service/src/main/java/vn/edu/examgrading/examgrading/repository/JdbcExamRepository.java
- `AuthController` --references--> `IdentityAuthService`  [EXTRACTED]
  identity-service/src/main/java/vn/edu/examgrading/identity/controller/AuthController.java → identity-service/src/main/java/vn/edu/examgrading/identity/service/IdentityAuthService.java
- `UserAccount` --references--> `UserRole`  [EXTRACTED]
  identity-service/src/main/java/vn/edu/examgrading/identity/domain/UserAccount.java → identity-service/src/main/java/vn/edu/examgrading/identity/domain/UserRole.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Reliability and Recovery Controls** — documentation_specification_source_09_risks_open_items_final_direct_upload_recovery, documentation_specification_source_09_risks_open_items_final_grading_reconciliation, documentation_specification_source_09_risks_open_items_final_retention_saga [EXTRACTED 1.00]

## Communities (101 total, 58 thin omitted)

### Community 0 - "org.springframework.web.server.ServerWebExchange"
Cohesion: 0.07
Nodes (11): CorrelationIdFilter, InternalPathNotFoundFilter, Counter, LoginRateLimitFilter, MinioPathNotFoundFilter, RequestLoggingFilter, StorageAccessFilter, StorageRateLimitFilter (+3 more)

### Community 1 - "SecurityConfig.java"
Cohesion: 0.05
Nodes (5): GatewaySecurityConfig, BootstrapAdmin, SecurityConfig, JwksController, RabbitMqConfig

### Community 2 - "App.jsx"
Cohesion: 0.13
Nodes (33): api(), API_BASE_URL, ApiError, gradingApi, upload(), accessTypes, App(), Button() (+25 more)

### Community 3 - "org.junit.jupiter.api.Test"
Cohesion: 0.10
Nodes (5): InternalPathNotFoundFilterTest, StorageAccessFilterTest, CursorCodec, CursorCodecTest, HmacSignerTest

### Community 4 - "IdentityExceptionHandler.java"
Cohesion: 0.15
Nodes (3): ApiError, GlobalExceptionHandler, IdentityExceptionHandler

### Community 5 - "package.json"
Cohesion: 0.09
Nodes (26): Frontend Demo HTML Entry Point, dependencies, react@^19.2.8, react-dom@^19.2.8, devDependencies, oxlint@^1.81.0, @types/react@^19.2.18, @types/react-dom@^19.2.7 (+18 more)

### Community 6 - "map"
Cohesion: 0.11
Nodes (4): CursorPage, ApiError, CouncilDecisionRequest, SubmissionController

### Community 7 - "interval"
Cohesion: 0.14
Nodes (24): healthcheck, ports, postgres-exam-grading, depends_on, healthcheck, interval, retries, test (+16 more)

### Community 8 - "app.auth"
Cohesion: 0.09
Nodes (22): app.auth, bootstrap-council-name, bootstrap-council-password, bootstrap-council-username, bootstrap-office-name, bootstrap-password, bootstrap-username, issuer (+14 more)

### Community 9 - "api-gateway/src/main/resources/application.yml"
Cohesion: 0.09
Nodes (21): app.login.rate-limit-per-minute, app.storage, max-pdf-bytes, rate-limit-per-minute, upstream-host, gateway, globalcors, routes (+13 more)

### Community 11 - "environment"
Cohesion: 0.13
Nodes (20): AUTH_LOCAL_ENABLED, BOOTSTRAP_ADMIN_PASSWORD, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_COUNCIL_NAME, BOOTSTRAP_COUNCIL_PASSWORD, BOOTSTRAP_COUNCIL_USERNAME, BOOTSTRAP_OFFICE_NAME, DB_PASSWORD (+12 more)

### Community 12 - "org.springframework.boot.autoconfigure.SpringBootApplication"
Cohesion: 0.18
Nodes (5): ApiGatewayApplication, ExamGradingServiceApplication, IdentityServiceApplication, ResultReviewServiceApplication, SubmissionServiceApplication

### Community 13 - "OutboxMessage"
Cohesion: 0.15
Nodes (5): IdempotentConsumer, InMemoryOutbox, OutboxMessage, OutboxPublisher, OutboxRelay

### Community 14 - "HmacSigner"
Cohesion: 0.18
Nodes (4): HmacSigner, SignedServiceRequest, SignedUserContext, FoundationPrimitivesTest

### Community 16 - "RedactingAuditLogger.java"
Cohesion: 0.18
Nodes (4): AuditEntry, AuditLogger, RedactingAuditLogger, SensitiveDataRedactor

### Community 17 - "IdentityAuthService"
Cohesion: 0.21
Nodes (3): DuplicateUserException, IdentityAuthService, UserNotFoundException

### Community 18 - "IdentityAuthService.java"
Cohesion: 0.17
Nodes (6): UserRole, COUNCIL, EXAM_OFFICE, GRADER, RegisterUserRequest, UserResponse

### Community 21 - "IdempotencyRecord"
Cohesion: 0.33
Nodes (4): IdempotencyExecutor, IdempotencyRecord, IdempotencyStore, InMemoryIdempotencyStore

### Community 23 - "spring"
Cohesion: 0.15
Nodes (12): app.internal.hmac-secret, password, url, username, management.endpoints.web.exposure.include, server.port, spring, application.name (+4 more)

### Community 25 - "SubmissionStatus"
Cohesion: 0.17
Nodes (11): SubmissionStatus, ANONYMIZING, COMPLETED, FAILED, FINALIZED, GRADING, READY_FOR_GRADING, REVIEW_REQUIRED (+3 more)

### Community 27 - "services"
Cohesion: 0.18
Nodes (11): minio, S3_ACCESS_KEY, S3_SECRET_KEY, condition, depends_on, entrypoint, environment, image (+3 more)

### Community 29 - "spring"
Cohesion: 0.18
Nodes (10): password, url, username, management.endpoints.web.exposure.include, server.port, spring, application.name, datasource (+2 more)

### Community 30 - "spring"
Cohesion: 0.18
Nodes (10): password, url, username, management.endpoints.web.exposure.include, server.port, spring, application.name, datasource (+2 more)

### Community 32 - "ReplayGuard"
Cohesion: 0.24
Nodes (4): InMemoryReplayGuard, InternalRequestVerifier, ReplayGuard, ServiceRequestVerifier

### Community 33 - "environment"
Cohesion: 0.20
Nodes (10): environment, EXAM_GRADING_URL, IDENTITY_URL, JWT_JWKS_URI, MAX_PDF_BYTES, RESULT_REVIEW_URL, S3_INTERNAL_ENDPOINT, S3_UPSTREAM_HOST (+2 more)

### Community 34 - "postgres-identity"
Cohesion: 0.33
Nodes (10): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, environment, environment, image, volumes, environment (+2 more)

### Community 37 - "spring"
Cohesion: 0.20
Nodes (9): password, url, username, enabled, addresses, spring, datasource, flyway (+1 more)

### Community 38 - "args"
Cohesion: 0.44
Nodes (9): build, SERVICE, args, context, dockerfile, build, build, build (+1 more)

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
Cohesion: 0.29
Nodes (7): postgres-submission, rabbitmq, condition, condition, submission-service, depends_on, healthcheck

### Community 50 - ".oxlintrc.json"
Cohesion: 0.33
Nodes (5): plugins, rules, react/only-export-components, react/rules-of-hooks, $schema

### Community 51 - "healthcheck"
Cohesion: 0.40
Nodes (5): postgres-identity, depends_on, healthcheck, condition, identity-service

### Community 52 - "healthcheck"
Cohesion: 0.40
Nodes (5): postgres-result-review, condition, depends_on, healthcheck, result-review-service

### Community 53 - "ExamStatus"
Cohesion: 0.40
Nodes (4): ExamStatus, ACTIVE, CLOSED, DRAFT

### Community 55 - "ReviewStatus"
Cohesion: 0.40
Nodes (4): ReviewStatus, ACTION_AUTHORIZED, OPEN, RESOLVED

### Community 56 - "healthcheck"
Cohesion: 0.50
Nodes (4): healthcheck, image, volumes, postgres-submission

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
- **31 isolated node(s):** `DRAFT`, `ACTIVE`, `CLOSED`, `EXAM_OFFICE`, `GRADER` (+26 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 343 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **58 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `IdentityAuthService` connect `IdentityAuthService` to `SecurityConfig.java`, `IdentityExceptionHandler.java`, `UserCredentialsResponse`, `UserAccountRepository`, `UserAccount`, `IdentityAuthService.java`, `AuthController.java`?**
  _High betweenness centrality (0.029) - this node is a cross-community bridge._
- **What connects `DRAFT`, `ACTIVE`, `CLOSED` to the rest of the system?**
  _31 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `org.springframework.web.server.ServerWebExchange` be split into smaller, more focused modules?**
  _Cohesion score 0.06766917293233082 - nodes in this community are weakly interconnected._
- **Why does `UserAccount` connect `UserAccount` to `UserAccountRepository`, `IdentityAuthService`, `IdentityAuthService.java`, `AuthController.java`, `uuid`?**
  _High betweenness centrality (0.017) - this node is a cross-community bridge._
- **Should `SecurityConfig.java` be split into smaller, more focused modules?**
  _Cohesion score 0.05406746031746032 - nodes in this community are weakly interconnected._
- **Why does `SubmissionStatus` connect `SubmissionStatus` to `InternalSubmissionController`, `FoundationPrimitivesTest.java`?**
  _High betweenness centrality (0.013) - this node is a cross-community bridge._
- **Should `App.jsx` be split into smaller, more focused modules?**
  _Cohesion score 0.12660028449502134 - nodes in this community are weakly interconnected._