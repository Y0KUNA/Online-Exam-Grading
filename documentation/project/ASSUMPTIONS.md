# Implementation assumptions

Defaults below are copied from §1.2 of `exam-grading-spec/10-implementation-spec.md` and are configuration-backed so they can be changed without altering domain code.

| Key | Default | Rationale |
|---|---:|---|
| `REMARK_ALLOW_AFTER_FINALIZED` | `true` | D-12 permits a remark while retained detail exists. |
| `REMARK_WINDOW_DAYS` | `0` | `0` means until the original retention deadline. |
| `AUTH_LOCAL_ENABLED` | `true` | Identity issues local JWTs for the MVP. |
| `JWT_TTL_MINUTES` | `15` | No refresh token in MVP. |
| `UPLOAD_TTL_MINUTES` | `10` | Default upload-policy validity. |
| `MAX_PDF_BYTES` | `52428800` | 50 MiB maximum PDF size. |

Other defaults to preserve while implementing: `needsReview` does not itself create a Review; non-cross-grading bulk assignment is round-robin; uploads and bulk assignments require an ACTIVE exam; templates with submissions are versioned rather than modified; export is blocked until every submission is finalized; a scan replacement with confirmed attempts returns `ATTEMPTS_EXIST`.
