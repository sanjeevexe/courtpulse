# Staging cost control and teardown

Always-on staging is estimated at roughly $80-100 per month in us-east-1 (the table and its
assumptions are in [the deployment guide](../deployment/aws-staging.md#what-costs-money)). The default
budget is $25. The budget only sends email; it never stops resources. Destroying the stack is the
only hard stop.

## Before the first apply

- In the Billing console, create an account-level **zero-spend or monthly cost budget** that is not
  managed by this stack, so an alert survives teardown.
- Turn on **Cost Anomaly Detection** (free) for the account.
- Decide the usage pattern: create, demonstrate, and destroy is the pattern that fits $25.

## While it is running

- Check **Billing > Bills** and **Cost Explorer** daily; data lags by up to a day. Activate the
  `Application` cost allocation tag once to filter CourtPulse spend (it applies from activation
  onward). The Cost Explorer API charges per request; the console does not.
- Budget emails arrive at 50, 80, and 100 percent of actual spend and 100 percent of forecast.
  Treat the forecast email as the signal to destroy or scale down.
- The largest fixed charges are the ALB (~$17/month) and public IPv4 addresses ($0.005 per hour
  each: two for the ALB plus one per running task). Every task you scale up adds an address.
- Cheaper while idle, in order of effect:
  1. `terraform destroy` (via `scripts/aws/teardown.sh`): stops everything except the state bucket.
  2. `enable_nightly_shutdown = true`: services at zero and RDS stopped overnight and on weekends.
     ALB, its addresses, storage, and secrets keep billing (roughly $30/month floor).
  3. Scale by hand for a quiet day (Terraform restores counts on the next apply):

     ```bash
     for service in $ECS_SERVICES; do
       aws ecs update-service --cluster "$ECS_CLUSTER" --service "$service" --desired-count 0 \
         --query 'service.serviceName' --output text
     done
     aws rds stop-db-instance --db-instance-identifier courtpulse-staging-postgres
     ```

     RDS starts itself again after seven days stopped.
- Keep `worker_capacity_provider = "FARGATE_SPOT"`, `ingestor_desired_count = 0` unless needed,
  `enable_tracing = false`, `enable_alb_access_logs = false`, and `enable_container_insights = false`.
- Keep `log_retention_days` at 14 or lower; ingestion beyond 5 GB/month is billed.

## Teardown

```bash
scripts/aws/teardown.sh
```

The script shows the AWS identity, requires typing `courtpulse-staging`, and then asks before each
step:

1. If nightly shutdown stopped RDS, it offers to start it (a stopped instance cannot be modified).
2. `terraform apply -var=deletion_protection=false -var=ecr_force_delete=true`: RDS and the Cognito
   user pool refuse deletion while protected, and ECR refuses to delete repositories that still hold
   images unless `force_delete` is already in state. Terraform shows the plan and asks for approval.
3. `terraform destroy` with the same variables. CloudFront takes several minutes to disable and
   delete. The SPA bucket is emptied because its content is reproducible.
4. Optionally deregisters and deletes leftover task-definition revisions (free, but clutter).

To destroy without the final RDS snapshot (data is disposable), set `skip_final_snapshot = true` in
`terraform.tfvars` first; the script's apply step records it. To delete the provider-key secret
immediately instead of after its recovery window, set `secret_recovery_window_days = 0` as well.

## After teardown

Verify the next day that nothing billable remains:

- **Resource Groups > Tag Editor**: search all regions for `Application = courtpulse`.
- RDS snapshots: `courtpulse-staging-postgres-final` remains unless skipped (snapshot storage is
  billed); delete it when you no longer need the data.
- CloudWatch log groups: RDS can recreate `/aws/rds/instance/courtpulse-staging-postgres/postgresql`
  while shutting down; delete it if present.
- Secrets Manager: the provider-key secret stays "scheduled for deletion" during its recovery window.
- The state bucket from `infra/terraform/bootstrap` remains (cents per month). Keep it to recreate
  staging later; to remove it, empty all object versions and remove `prevent_destroy` deliberately.
- Cost Explorer shows spend by service; any line that keeps growing after teardown is a leftover.

## Recreate

The state bucket and `backend.hcl` remain, so recreation is steps 2-9 of the deployment guide:
the targeted apply for registries, role, and budget (the repositories are new, so push images again),
then the full apply with `image_tag`, SES verification, a new Cognito user (users are not kept),
GitHub variables (IDs change), and a deploy. The canary re-imports the synthetic fixture into the new
database. Restoring the final snapshot instead of starting empty is a manual procedure based on
[database restore](database-restore.md).
