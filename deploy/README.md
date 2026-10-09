# Local infrastructure

Run `docker compose -f deploy/docker-compose.yml --env-file .env up -d` from the repository root. This file starts shared infrastructure and `minio-init`, which idempotently creates the private `original` and `anonymized` buckets.

Gateway storage pass-through is restricted to those two buckets. The `api-gateway` service itself is started separately until its service image is included in the deployment profile.
