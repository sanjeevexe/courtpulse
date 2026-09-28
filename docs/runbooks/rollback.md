# Staging rollback

Use this when a deploy is healthy enough to start but wrong: the canary or smoke test failed after
services were updated, alarms fired after a release, or a user-visible regression appeared. The
target is a completed rollback within 15 minutes. Tasks that never become healthy are already
handled by the ECS deployment circuit breaker, which returns the service to its previous revision
on its own; the deploy job then fails with "circuit breaker may have rolled it back".

## Decide

1. Note the failing commit (the deploy summary shows it) and the symptom. Do not paste tokens,
   addresses, or payloads into the incident notes.
2. If the problem is a **destructive or wrong migration**, rollback does not help: code rollback
   never reverts schema or data. Stop writers and follow [database restore](database-restore.md).
3. If the problem is **configuration only** (a Terraform variable), fix the variable, apply, and run
   the Deploy workflow instead; rollback would restore the old configuration and image together.
4. Otherwise roll back the code.

## Roll back

GitHub: **Actions > Roll back staging > Run workflow** on `main`.

- Leave `image_tag` empty to return to the previous CI deployment. The workflow reads the API
  service's task-definition history and selects the newest CI-registered revision older than the
  running one with a different image tag. Terraform-registered revisions are never chosen.
- Or enter a full 40-character SHA whose images are still in ECR (the lifecycle policy keeps the
  newest 20 tagged images per repository).
- Keep `include_web` checked so the SPA is rebuilt from the same commit; otherwise the new SPA may
  call endpoints the old API lacks.

The workflow shares the deploy concurrency group, so it waits for a running deploy instead of
racing it. It re-points every service to the newest CI revision for the target tag (the exact
task definition that ran before, including its configuration at the time), registers a revision
from the current template only if no such revision remains, waits until each primary deployment
has completed on that revision, republishes and invalidates the SPA, and runs the smoke test.

Locally, with the same variables exported (see `docs/deployment/aws-staging.md`):

```bash
tag="$(scripts/aws/rollback.sh resolve)"        # or: scripts/aws/rollback.sh resolve <sha>
scripts/aws/rollback.sh apply "$tag"
git switch --detach "$tag" && (cd apps/web && npm ci && npm run build) && git switch -
scripts/aws/publish-web.sh apps/web/dist && scripts/aws/invalidate-web.sh /index.html
scripts/aws/smoke-test.sh "$CLOUDFRONT_URL"
```

## Verify

- The workflow summary shows the target tag, and the smoke test passed.
- `aws ecs describe-services --cluster "$ECS_CLUSTER" --services <service>` shows one PRIMARY
  deployment in `COMPLETED` for each service.
- ALB 5xx, unhealthy hosts, queue age, and DLQ alarms return to `OK`. Check the processor and
  delivery logs for repeated failures on redelivered messages.
- Leases, SQS redelivery, and PostgreSQL idempotency make a mid-flight worker switch safe; a
  delivery email might still be sent twice (ADR 0009), never recorded twice.

## Why rollback is safe: expand-and-contract migrations

Rollback runs the previous release against the current schema. Every Flyway migration must keep
release N-1 working on schema N:

1. **Expand.** Add new tables or nullable columns, and new indexes, in a release that can still run
   without them. Code in that release writes both shapes if a shape changes.
2. **Migrate data** in a separate, idempotent step, and observe it complete.
3. **Contract** (drop, rename, `NOT NULL`, tightened constraints) only in a *later* release, after
   no running or rollback-eligible version reads the old shape.
4. Never edit or delete an applied migration; Flyway validates checksums.
5. Flyway's default `ignoreMigrationPatterns` (`*:future`) lets older code start when the database
   has newer applied migrations. Do not change it, and do not enable `outOfOrder`.
6. Review each migration with the question "does the previous release still pass its tests on
   this schema?" A migration that fails that question needs a restore plan, not a rollback plan.

The `--migrate` one-off task runs before services change, and the API also runs Flyway at startup
under a PostgreSQL advisory lock, so concurrent starts are safe.

## Afterwards

Record the failing SHA, the symptom, the rollback duration, and the root cause. Fix forward with a
new commit and let the normal deploy run; do not re-run the failed SHA. If the rolled-back release
must stay for a while, set Terraform's `image_tag` to it so any template revision Terraform
registers uses a known-good image.
