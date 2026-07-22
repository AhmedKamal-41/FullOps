# Remote state lives in S3 with native S3 state locking. The bucket must exist before
# `terraform init` — create it once, out of band, with versioning and encryption enabled.
#
# This block is intentionally empty so no account-specific values are committed. Supply
# them at init time:
#
#   terraform init -backend-config=backend.hcl
#
# where backend.hcl contains bucket / key / region / use_lockfile = true. See README.md.
#
# CI never initializes this backend: it runs `terraform init -backend=false`, so no state
# bucket or credentials are needed to fmt/validate.
terraform {
  backend "s3" {}
}
