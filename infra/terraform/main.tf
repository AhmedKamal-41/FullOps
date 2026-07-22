# Root module for the OPTIONAL AWS reference architecture. This composes the network and
# database building blocks. It is validated in CI but never applied automatically — see
# README.md for what it would and would not provision.

locals {
  name_prefix = "${var.project}-${var.environment}"
}

module "network" {
  source = "./modules/network"

  name_prefix        = local.name_prefix
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
}

module "database" {
  source = "./modules/database"

  name_prefix          = local.name_prefix
  vpc_id               = module.network.vpc_id
  private_subnet_ids   = module.network.private_subnet_ids
  vpc_cidr             = var.vpc_cidr
  instance_class       = var.db_instance_class
  allocated_storage_gb = var.db_allocated_storage_gb
  engine_version       = var.db_engine_version
}
