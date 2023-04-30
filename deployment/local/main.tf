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
  source           = "./modules/local-infra"
  k8s_cluster_name = var.k8s_cluster_name
}

module "app-config" {
  source                 = "../shared-terraform/modules/app-config"
  be_app_port            = 8081
  dev_mode               = true
  postgres_database_name = module.local-infra.postgres_database_name
  postgres_host          = module.local-infra.postgres_host
  postgres_port          = module.local-infra.postgres_port
  postgres_user          = module.local-infra.postgres_superuser
  postgres_password      = module.local-infra.postgres_superuser_password
}
