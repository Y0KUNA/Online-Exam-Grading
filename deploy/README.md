# Local infrastructure

Run `docker compose -f deploy/docker-compose.yml --env-file .env up -d` from the repository root. This file starts only shared infrastructure; application images are intentionally added once each service has a tested Dockerfile.

Buckets `original` and `anonymized` must be private and provisioned by an idempotent bootstrap job before upload work is enabled.
