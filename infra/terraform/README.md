# AWS reference architecture (Terraform) — OPTIONAL, never auto-applied

This directory is an **optional reference**. It has **not been applied** to any AWS
account, and nothing in this repository ever runs `terraform apply`. CI runs only
`terraform fmt`, `terraform validate`, `tflint`, and a Trivy IaC security scan
(`.github/workflows/terraform.yml`). No cloud resources are provisioned and no cost is
incurred by this project.

## What it models

- `modules/network` — a VPC with private subnets across two AZs. Deliberately **no NAT
  gateway** (a real hourly + per-GB cost driver) and no internet gateway; only what a
  private database needs.
- `modules/database` — a single, small RDS PostgreSQL instance (`db.t4g.micro`, gp3,
  encrypted, single-AZ). The master password is **managed by RDS and stored in Secrets
  Manager** — it never appears in a variable or in state as plaintext. Each service keeps
  its own database on this shared instance, preserving the database-per-service boundary
  without paying for four instances.

## What it deliberately leaves out (and why)

A full production stack would add a compute tier (ECS Fargate services behind an internal
ALB), Amazon MSK (or MSK Serverless) for Kafka, ElastiCache for Redis, and either Amazon
Cognito or a self-managed Keycloak for OIDC. Those are described here but not coded,
because the expensive, cost-driving pieces (NAT gateways, MSK brokers, ALBs, multi-AZ RDS)
are exactly what a portfolio should not spin up by accident. The network + database modules
show the pattern (composable modules, RDS-managed secrets, private-only networking, tagging,
remote state) without that exposure.

## Cost drivers to be aware of before ever applying this

| Driver | Note |
| --- | --- |
| RDS instance-hours | `db.t4g.micro` single-AZ is the cheapest realistic default. Multi-AZ doubles it. |
| RDS storage + backups | 20 GB gp3 + 7 days of backups. |
| NAT gateway | **Not created here.** It is one of the largest surprise costs in an AWS VPC — hourly + per-GB. |
| MSK / ALB (if the compute tier is added) | Per-hour broker and load-balancer charges; the biggest drivers in the fuller design. |

## Remote state

State lives in S3 with native S3 locking (`use_lockfile`, no DynamoDB table needed). The
bucket must exist before `terraform init`, with versioning and encryption on. Backend
values are supplied at init time so nothing account-specific is committed:

```
cp backend.hcl.example backend.hcl   # fill in your bucket
terraform init -backend-config=backend.hcl
```

## Secrets handling

- The RDS master password is created and rotated by RDS, stored in Secrets Manager, and
  referenced only by ARN (`db_master_secret_arn` output). No password is passed in.
- Service database credentials would be read from Secrets Manager by the compute tier at
  runtime (via the ECS task execution role), not baked into images or task definitions.

## Destroy procedure

```
terraform init -backend-config=backend.hcl
terraform destroy
```

Because the dev defaults set `deletion_protection = false` and `skip_final_snapshot = true`,
destroy is clean and leaves nothing behind. For a real environment, flip both to their safe
values (`deletion_protection = true`, `skip_final_snapshot = false`) and take a final
snapshot before tearing down.

## Local validation (no AWS account, no cost)

```
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
tflint --recursive
```
