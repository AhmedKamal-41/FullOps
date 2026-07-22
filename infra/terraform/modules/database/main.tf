# A single, cost-conscious RDS PostgreSQL instance. Each service owns its own database on
# this instance (created by the services' own Flyway migrations against per-service
# credentials) — the "database per service" boundary is preserved without paying for four
# separate instances. Stricter isolation would use one instance per service; that is a
# cost/isolation trade-off documented in README.md.
#
# The master password is managed by RDS and stored in Secrets Manager (no password ever
# appears in a variable, in state as plaintext, or in this code).

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db"
  description = "PostgreSQL access from within the VPC only."
  vpc_id      = var.vpc_id

  ingress {
    description = "PostgreSQL from within the VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage = var.allocated_storage_gb
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  username                    = "fulfillops"
  manage_master_user_password = true

  backup_retention_period = 7
  # Dev defaults: no deletion protection and no final snapshot so `terraform destroy` is
  # clean. Set both to the safe values (deletion_protection = true, skip = false) for a
  # real environment.
  deletion_protection = false
  skip_final_snapshot = true
}
