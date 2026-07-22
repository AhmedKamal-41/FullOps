output "vpc_id" {
  description = "ID of the created VPC."
  value       = module.network.vpc_id
}

output "private_subnet_ids" {
  description = "IDs of the private subnets."
  value       = module.network.private_subnet_ids
}

output "db_endpoint" {
  description = "Connection endpoint (host:port) of the RDS PostgreSQL instance."
  value       = module.database.endpoint
}

output "db_master_secret_arn" {
  description = "ARN of the Secrets Manager secret holding the RDS-managed master password."
  value       = module.database.master_user_secret_arn
}
