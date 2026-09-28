# Staging database restore

Use this after data loss or a harmful migration. The engineering-plan targets are RPO under five
minutes (RDS point-in-time recovery normally trails the present by about five minutes) and RTO under
60 minutes. The procedure restores beside the damaged instance, adopts the copy in Terraform, lets
the normal deploy attach every task, and proves integrity with the replay canary before the old
instance is deleted. A restored instance is billed like the original while both exist.

## 1. Choose the restore point

```bash
db_id="$(terraform -chdir=infra/terraform/environments/staging output -raw database_identifier)"
aws rds describe-db-instances --db-instance-identifier "$db_id" \
  --query 'DBInstances[0].[LatestRestorableTime, BackupRetentionPeriod, DBInstanceStatus]' --output text
```

Pick a UTC time before the incident, using deploy summaries, alarm times, and application logs.
Seven days of backups are kept. A stopped instance (nightly shutdown) must be started first.

## 2. Stop writers

Scale the workers to zero so nothing new lands in the damaged database. The API may keep serving
reads; user writes made during the procedure will not be in the restored copy.

```bash
for service in processor delivery reconciliation ingestor; do
  aws ecs update-service --cluster "$ECS_CLUSTER" --service "courtpulse-staging-${service}" \
    --desired-count 0 --query 'service.serviceName' --output text
done
```

The next `terraform apply` restores Terraform's desired counts.

## 3. Restore beside the original

Reuse the stack's subnet group, security group, and parameter group so the copy is equally private
and TLS-only. `--manage-master-user-password` gives the copy its own RDS-managed secret.

```bash
tf() { terraform -chdir=infra/terraform/environments/staging output -raw "$1"; }
restored_id="${db_id}-r$(date -u +%m%d%H%M)"
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier "$db_id" \
  --target-db-instance-identifier "$restored_id" \
  --restore-time 2026-09-28T14:05:00Z \
  --db-instance-class db.t4g.micro \
  --storage-type gp3 \
  --db-subnet-group-name "$(tf database_subnet_group_name)" \
  --vpc-security-group-ids "$(tf database_security_group_id)" \
  --db-parameter-group-name "$(tf database_parameter_group_name)" \
  --no-publicly-accessible --no-multi-az \
  --manage-master-user-password \
  --deletion-protection --copy-tags-to-snapshot \
  --enable-cloudwatch-logs-exports postgresql \
  --query 'DBInstance.DBInstanceIdentifier' --output text
aws rds wait db-instance-available --db-instance-identifier "$restored_id"
```

(Use `--use-latest-restorable-time` instead of `--restore-time` to recover everything up to now.)

## 4. Adopt the copy in Terraform

Terraform tracks one instance. Detach the damaged one from state (it keeps running and keeps its
deletion protection), import the copy under the same address, and point the configuration at it:

```bash
cd infra/terraform/environments/staging
terraform state rm module.data.aws_db_instance.this
terraform import module.data.aws_db_instance.this "$restored_id"
echo "db_identifier = \"${restored_id}\"" >> terraform.tfvars
terraform plan -out=restore.tfplan
```

Read the plan before applying. Expect in-place updates on the restored instance (for example
Performance Insights), a new log group named for the new identifier, updated execution-role
policies for the new secret ARN, and new task-definition template revisions whose
`COURTPULSE_DB_URL` and secret references point at the copy. There must be **no** destroy or
replace of `module.data.aws_db_instance.this`. Then:

```bash
terraform apply restore.tfplan
```

## 5. Attach tasks and verify integrity

Running tasks still use the old instance until they are redeployed. Run **Actions > Deploy
staging** from `main` (or run the scripts locally with the currently deployed SHA). The deploy
copies the new templates, so it:

1. runs `--migrate` against the copy (it applies any migration the restore point predates);
2. rolls every service onto the copy and waits for completed deployments;
3. runs `--canary`, which imports the synthetic fixture idempotently and waits for its known
   final-state checksum (`06d40d7e...c3bca`), proving schema, processor, SQS, and data agree;
4. runs the smoke test.

Then compare with what you expected at the restore point, using an operations token: the aggregate
`/api/v1/operations/deliveries` and `/api/v1/operations/reconciliations` counts, and the public
`/api/v1/games` list. There is no bastion host; a read-only SQL session requires a temporary ECS Exec
task and is deliberately not part of this runbook.

Scale workers back with `terraform apply` (it restores the configured desired counts), and confirm
queue age drains and DLQ alarms stay `OK`.

## 6. Retire the damaged instance

Only after verification, and keep a snapshot in case the investigation needs it:

```bash
aws rds modify-db-instance --db-instance-identifier "$db_id" --no-deletion-protection --apply-immediately
aws rds delete-db-instance --db-instance-identifier "$db_id" \
  --final-db-snapshot-identifier "${db_id}-pre-restore-$(date -u +%Y%m%d)"
```

Its RDS-managed secret is deleted with it. Delete the snapshot when it is no longer needed
(snapshot storage is billed). Leave `db_identifier` set in `terraform.tfvars`; removing it would
make Terraform replace the instance.

## Rehearsal

Rehearse steps 1-5 with `--use-latest-restorable-time` after a deploy, time them, and record the
achieved RTO. Rehearsals cost roughly one extra micro-instance for the hour they take.
