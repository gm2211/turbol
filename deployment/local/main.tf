// Server dev config
resource "local_file" "runtime-yml" {
  filename = "${path.root}/runtime.yml"
  content  = yamlencode(module.app-config.runtime_config)
}
resource "local_file" "install-yml" {
  filename = "${path.root}/install.yml"
  content  = yamlencode(module.app-config.install_config)
}
resource "local_file" "secrets-yml" {
  filename = "${path.root}/secrets.yml"
  content  = yamlencode(module.app-config.install_secrets)
}

// Modules

module "local-infra" {
  source = "./modules/local-infra"
  k8s_cluster_name = var.k8s_cluster_name
}

module "populate-dev-vault" {
  source               = "./modules/populate-dev-vault"
  vault_address        = module.local-infra.vault_address
  vault_dev_root_token = module.local-infra.vault_dev_root_token
}

module "populate-vault-db-roles" {
  source                      = "../backend-terraform/modules/populate-vault-db-roles"
  environment_name            = "dev"
  postgres_database_name      = module.local-infra.postgres_database_name
  postgres_hostname           = module.local-infra.postgres_host
  postgres_port               = module.local-infra.postgres_port
  postgres_connection_args    = "?sslmode=disable"
  postgres_superuser          = module.local-infra.postgres_superuser
  postgres_superuser_password = module.local-infra.postgres_superuser_password
  vault_address               = module.local-infra.vault_address
  vault_root_token            = module.local-infra.vault_dev_root_token
  vault_kv_name               = "secret"
}

module "app-config" {
  source                                = "../backend-terraform/modules/app-config"
  app_port                              = 8050
  frontend_assets_path                  = "frontend/target/static/web"
  postgres_database_name                = module.local-infra.postgres_database_name
  postgres_host                         = module.local-infra.postgres_host
  postgres_port                         = module.local-infra.postgres_port
  vault_address                         = module.local-infra.vault_address
  vault_authz_path                      = module.populate-dev-vault.vault_dev_authz_path
  vault_oauth2_path                     = module.populate-dev-vault.vault_dev_oauth_path
  vault_postgres_admin_role_name        = module.populate-vault-db-roles.postgres_admin_role_name
  vault_postgres_read_only_role_name    = module.populate-vault-db-roles.postgres_read_only_role_name
  vault_postgres_read_write_role_name   = module.populate-vault-db-roles.postgres_read_write_role_name
  vault_postgres_roles_path             = module.populate-vault-db-roles.postgres_roles_path
  vault_postgres_secrets_path           = module.populate-vault-db-roles.postgres_secrets_path
  vault_postgres_superuser_password_key = module.populate-vault-db-roles.postgres_superuser_password_key
  vault_root_token                      = module.local-infra.vault_dev_root_token
}
