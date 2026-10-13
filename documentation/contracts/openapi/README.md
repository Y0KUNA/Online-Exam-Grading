# OpenAPI contracts

Place one public contract per bounded service here (`identity.yaml`, `submission.yaml`, `exam-grading.yaml`, `result-review.yaml`). Internal endpoints remain in the owning service contract under `/internal/**`; the gateway must never route them.

The source contract is `exam-grading-spec/source/05-api-contracts-ver-final.md`, amended by `exam-grading-spec/10-implementation-spec.md`.
