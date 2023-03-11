locals {
  app_port = 443

  postgres_database_name = var.environment_name
}

module "app" {
  source                                = "../../../backend-terraform/modules/app"
  app_image_name                        = "turbol"
  app_name                              = var.app_name
  app_port                              = local.app_port
  app_version                           = var.app_version
  dockerhub_username                    = "gm221122"
  dockerhub_password                    = var.dockerhub_password
  domain                                = var.domain
  k8s_cluster_ca                        = var.k8s_cluster_ca
  k8s_cluster_host                      = var.k8s_host
  k8s_cluster_token                     = var.k8s_token
  postgres_database_name                = local.postgres_database_name
  postgres_host                         = var.postgres_host
  postgres_port                         = var.postgres_port
  vault_address                         = var.vault_address
  # These secrets are manually entered, not via terraform
  vault_authz_path                      = "${module.populate-vault-db-roles.vault_kv_name}/${var.environment_name}-authz"
  # These secrets are manually entered, not via terraform
  vault_oauth2_path                     = "${module.populate-vault-db-roles.vault_kv_name}/${var.environment_name}-oauth"
  vault_postgres_secrets_path           = module.populate-vault-db-roles.postgres_secrets_path
  vault_postgres_roles_path             = module.populate-vault-db-roles.postgres_roles_path
  vault_postgres_superuser_password_key = module.populate-vault-db-roles.postgres_superuser_password_key
  vault_postgres_admin_role_name        = module.populate-vault-db-roles.postgres_admin_role_name
  vault_postgres_read_only_role_name    = module.populate-vault-db-roles.postgres_read_only_role_name
  vault_postgres_read_write_role_name   = module.populate-vault-db-roles.postgres_read_write_role_name
  vault_root_token                      = var.vault_root_token
}

module "populate-vault-db-roles" {
  source                      = "../../../backend-terraform/modules/populate-vault-db-roles"
  environment_name            = var.environment_name
  postgres_database_name      = local.postgres_database_name
  postgres_hostname           = var.postgres_host
  postgres_port               = var.postgres_port
  postgres_superuser          = var.postgres_superuser
  postgres_superuser_password = var.postgres_superuser_password
  vault_address               = var.vault_address
  vault_root_token            = var.vault_root_token
  vault_kv_name               = "kv"
}