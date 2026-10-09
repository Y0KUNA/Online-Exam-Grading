# ADR-001: Presigned MinIO access through the Gateway

## Decision

MinIO is private on the application network. Client uploads/downloads only use presigned requests through Gateway paths `/storage/original/**` and `/storage/anonymized/**`. Gateway strips `/storage`, preserves the signed method/query/body and does not attach JWT or internal HMAC headers.

## Controls

- Only the two storage bucket prefixes are routed; unknown `/storage/**` paths have no route.
- A presigned POST policy fixes the object key and allowed content-length; a presigned GET expires quickly.
- MinIO buckets are created idempotently at Compose startup and anonymous access is explicitly disabled.
- Gateway must stream large requests: do not buffer upload bodies in controllers or filters. Range headers pass through unchanged for PDF viewing.

## Verification required before accepting S0

Run AT-B02 and AT-N01…AT-N12 against the running Compose stack, including a 50 MiB POST and a Range GET. Record exact results in `docs/IMPLEMENTATION_PLAN.md`.
