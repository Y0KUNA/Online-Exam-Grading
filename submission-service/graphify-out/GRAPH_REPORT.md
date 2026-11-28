# Graph Report - submission-service  (2026-11-26)

## Corpus Check
- Corpus is ~32,152 words - fits in a single context window. You may not need a graph.

## Summary
- 936 nodes · 3365 edges · 25 communities (14 shown, 11 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 529 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23

## God Nodes (most connected - your core abstractions)
1. `SubmissionStatus` - 49 edges
2. `SubmissionUploadServiceImplTest` - 49 edges
3. `SubmissionRepositoryMarker` - 47 edges
4. `Submission` - 46 edges
5. `StorageProperties` - 46 edges
6. `ObjectStorageGateway` - 44 edges
7. `SubmissionEventRepository` - 40 edges
8. `AnonymizationWorkerTest` - 38 edges
9. `IdentityClient` - 37 edges
10. `SubmissionUploadServiceImpl` - 36 edges

## Surprising Connections (you probably didn't know these)
- `AnonymizationWorker` --references--> `PageMasker`  [EXTRACTED]
  src/main/java/vn/edu/examgrading/submission/messaging/AnonymizationWorker.java → src/main/java/vn/edu/examgrading/submission/anonymize/PageMasker.java
- `AnonymizationWorkerTest` --references--> `PageMasker`  [EXTRACTED]
  src/test/java/vn/edu/examgrading/submission/messaging/AnonymizationWorkerTest.java → src/main/java/vn/edu/examgrading/submission/anonymize/PageMasker.java
- `SubmissionUploadServiceImpl` --references--> `IdentityClient`  [EXTRACTED]
  src/main/java/vn/edu/examgrading/submission/service/SubmissionUploadServiceImpl.java → src/main/java/vn/edu/examgrading/submission/client/IdentityClient.java
- `ProcessingJobReconcilerTest` --references--> `IdentityClient`  [EXTRACTED]
  src/test/java/vn/edu/examgrading/submission/job/ProcessingJobReconcilerTest.java → src/main/java/vn/edu/examgrading/submission/client/IdentityClient.java
- `SchedulerJobsIT` --references--> `IdentityClient`  [EXTRACTED]
  src/test/java/vn/edu/examgrading/submission/job/SchedulerJobsIT.java → src/main/java/vn/edu/examgrading/submission/client/IdentityClient.java

## Import Cycles
- None detected.

## Communities (25 total, 11 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (33): IdentityClient, ResultClient, SubmissionError, SubmissionStatus, ANONYMIZING, COMPLETED, FAILED, FINALIZED (+25 more)

### Community 1 - "Community 1"
Cohesion: 0.07
Nodes (11): MaskException, ReviewRequest, PseudonymResponse, AnonymizeJobMessage, ValidateJobMessage, ValidationException, OriginalFile, TemplateInfo (+3 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (10): Submission, SubmissionIdentity, UpdatePseudonymRequest, SubmissionOutbox, ExamFileVersion, StaleJob, CursorPosition, SubmissionUploadServiceImpl (+2 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (11): LimitExceededException, PageMasker, RenderedSide, JdbcPaperTemplateRepository, CropRegion, CutRegion, MaskRegions, RedactionRegion (+3 more)

### Community 4 - "Community 4"
Cohesion: 0.07
Nodes (7): ClockConfig, HttpClientConfig, RabbitMqConfig, SchedulingConfig, PseudonymRequest, SubmissionBindingReleaseRequest, SubmissionBindingRequest

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (9): PageInfo, PaperTemplateController, TypeReference<>, TypeReference<>, TypeReference<>, TypeReference<>, SubmissionEventsController, EventRow (+1 more)

### Community 6 - "Community 6"
Cohesion: 0.13
Nodes (4): InternalSubmissionController, AccessTicket, InternalSubmissionControllerIT, InternalSubmissionControllerTest

### Community 7 - "Community 7"
Cohesion: 0.06
Nodes (5): StorageConfig, StoredObject, UploadTicket, S3ObjectStorage, S3ObjectStorageTest

### Community 8 - "Community 8"
Cohesion: 0.08
Nodes (5): JdbcExamFileRepository, JdbcProcessingJobRepository, Entry, InMemoryObjectStorage, SchedulerJobsIT

### Community 9 - "Community 9"
Cohesion: 0.05
Nodes (43): app, app.internal.hmac-secret, jobs, pdf, storage, upload, password, url (+35 more)

### Community 12 - "Community 12"
Cohesion: 0.17
Nodes (3): SubmissionController, CompleteSubmissionResponse, SubmissionServiceMarker

### Community 13 - "Community 13"
Cohesion: 0.18
Nodes (3): InitSubmissionRequest, InitSubmissionResponse, Upload

### Community 14 - "Community 14"
Cohesion: 0.11
Nodes (17): app, storage, password, url, username, enabled, spring, datasource (+9 more)

### Community 20 - "Community 20"
Cohesion: 0.20
Nodes (9): password, url, username, enabled, addresses, spring, datasource, flyway (+1 more)

## Knowledge Gaps
- **10 isolated node(s):** `UPLOADING`, `UPLOADED`, `VALIDATING`, `ANONYMIZING`, `READY_FOR_GRADING` (+5 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 158 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `StorageProperties` connect `Community 0` to `Community 1`, `Community 2`, `Community 6`, `Community 7`, `Community 8`, `Community 11`, `Community 15`, `Community 16`?**
  _High betweenness centrality (0.045) - this node is a cross-community bridge._
- **What connects `UPLOADING`, `UPLOADED`, `VALIDATING` to the rest of the system?**
  _10 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.0611646418098031 - nodes in this community are weakly interconnected._
- **Why does `ObjectStorageGateway` connect `Community 0` to `Community 1`, `Community 2`, `Community 6`, `Community 7`, `Community 8`, `Community 16`?**
  _High betweenness centrality (0.040) - this node is a cross-community bridge._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.06514851485148515 - nodes in this community are weakly interconnected._
- **Why does `SubmissionStatus` connect `Community 0` to `Community 1`, `Community 2`, `Community 5`, `Community 10`, `Community 15`, `Community 17`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.05818181818181818 - nodes in this community are weakly interconnected._