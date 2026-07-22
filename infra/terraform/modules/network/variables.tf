variable "name_prefix" {
  description = "Prefix for resource names, e.g. fulfillops-dev."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
}

variable "availability_zones" {
  description = "Availability zones to spread the private subnets across."
  type        = list(string)
}
