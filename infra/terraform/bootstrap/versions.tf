terraform {
  # 1.10 is the first release with S3-native state locking (use_lockfile).
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
  }

  # The bootstrap root intentionally keeps local state: it creates the bucket
  # that every other root uses for remote state. Keep terraform.tfstate from
  # this directory somewhere safe; losing it only means re-importing one bucket.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Application = var.project
      Environment = "shared"
      Owner       = var.owner
      CostCenter  = var.cost_center
      ManagedBy   = "terraform"
    }
  }
}
