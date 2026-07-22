# A minimal, cost-conscious network: one VPC with private subnets across the given AZs.
# There is deliberately no NAT gateway (a real hourly + per-GB cost driver) — this
# reference keeps only what a private RDS instance needs. Adding public subnets, an
# internet gateway, and an ALB for the ECS compute tier is described in README.md.

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name_prefix}-vpc"
  }
}

resource "aws_subnet" "private" {
  count = length(var.availability_zones)

  vpc_id            = aws_vpc.this.id
  availability_zone = var.availability_zones[count.index]
  # Carve a /24 per subnet out of the VPC CIDR.
  cidr_block = cidrsubnet(var.vpc_cidr, 8, count.index)

  tags = {
    Name = "${var.name_prefix}-private-${var.availability_zones[count.index]}"
    Tier = "private"
  }
}
