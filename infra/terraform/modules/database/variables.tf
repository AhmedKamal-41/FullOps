variable "name_prefix" {
  description = "Prefix for resource names, e.g. fulfillops-dev."
  type        = string
}

variable "vpc_id" {
  description = "VPC to place the database and its security group in."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for the DB subnet group."
  type        = list(string)
}

variable "vpc_cidr" {
  description = "VPC CIDR allowed to reach PostgreSQL on 5432."
  type        = string
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "allocated_storage_gb" {
  description = "Allocated storage in GB."
  type        = number
}

variable "engine_version" {
  description = "PostgreSQL engine version."
  type        = string
}
