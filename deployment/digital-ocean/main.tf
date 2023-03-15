// Providers
provider "digitalocean" {
  token = var.digitalocean_api_token
}

// Data
data "digitalocean_kubernetes_cluster" "k8s-turbol" {
  name = "k8s-turbol"
}

// Locals
locals {
  domain                       = "turbol.live"
  // K8s - digitalocean
  digital_ocean_k8s_host       = data.digitalocean_kubernetes_cluster.k8s-turbol.endpoint
  digital_ocean_k8s_token      = data.digitalocean_kubernetes_cluster.k8s-turbol.kube_config[0].token
  digital_ocean_k8s_cluster_ca = base64decode(
  data.digitalocean_kubernetes_cluster.k8s-turbol.kube_config[0].cluster_ca_certificate
  )
  // App
  prod_app_name                = "turbol"
  staging_app_name             = "turbol-staging"
  app_port                     = 443
  // Postgres
  postgres_port                = 25060
  // See: https://cloud.digitalocean.com/databases/postgres?i=0eb48b
  postgres_host                = "private-postgres-do-user-6593469-0.a.db.ondigitalocean.com"
  postgres_superuser           = "doadmin"
}

// Modules

module "infra" {
  source                        = "./modules/infra"
  digital_ocean_k8s_cluster_ca  = local.digital_ocean_k8s_cluster_ca
  digital_ocean_k8s_host        = local.digital_ocean_k8s_host
  digital_ocean_k8s_token       = local.digital_ocean_k8s_token
  digitalocean_api_token       = var.digitalocean_api_token
  domain                        = local.domain
  external_dns_helm_stable_repo = "https://charts.bitnami.com/bitnami"
  helm_jetstack_repo            = "https://charts.jetstack.io"
  nginx_helm_stable_repo        = "https://kubernetes.github.io/ingress-nginx"
  prod_app_name                 = local.prod_app_name
  prod_app_port                 = local.app_port
  staging_app_name              = local.staging_app_name
  staging_app_port              = local.app_port
}

module "prod" {
  source                      = "./modules/app-and-db"
  environment_name            = "prod"
  app_name                    = local.prod_app_name
  app_version                 = var.prod_app_version
  dockerhub_password          = var.docker_hub_password
  domain                      = local.domain
  k8s_cluster_ca              = local.digital_ocean_k8s_cluster_ca
  k8s_host                    = local.digital_ocean_k8s_host
  k8s_token                   = local.digital_ocean_k8s_token
  postgres_host               = local.postgres_host
  postgres_port               = local.postgres_port
  postgres_superuser          = local.postgres_superuser
  postgres_superuser_password = var.postgres_superuser_password
}

#module "staging" {
#  source                      = "./modules/app-and-db"
#  environment_name            = "staging"
#  app_name                    = local.staging_app_name
#  app_version                 = var.staging_app_version
#  dockerhub_password          = var.docker_hub_password
#  domain                      = local.domain
#  k8s_cluster_ca              = local.digital_ocean_k8s_cluster_ca
#  k8s_host                    = local.digital_ocean_k8s_host
#  k8s_token                   = local.digital_ocean_k8s_token
#  postgres_host               = local.postgres_host
#  postgres_port               = local.postgres_port
#  postgres_superuser          = local.postgres_superuser
#  postgres_superuser_password = var.postgres_superuser_password
#  vault_address               = module.infra.vault_address
#  vault_root_token            = var.vault_root_token
#}
