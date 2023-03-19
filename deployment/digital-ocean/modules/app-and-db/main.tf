locals {
  postgres_database_name = var.environment_name
}

module "app" {
  source                                = "../../../shared-terraform/modules/app"
  app_image_name                        = "turbol"
  app_name                              = var.app_name
  app_port                              = var.app_port
  app_version                           = var.app_version
  dockerhub_username                    = "gm2211"
  dockerhub_password                    = var.dockerhub_password
  domain                                = var.domain
  k8s_cluster_ca                        = var.k8s_cluster_ca
  k8s_cluster_host                      = var.k8s_host
  k8s_cluster_token                     = var.k8s_token
  postgres_database_name                = local.postgres_database_name
  postgres_host                         = var.postgres_host
  postgres_port                         = var.postgres_port
  postgres_user                         = var.postgres_superuser
  postgres_password                     = var.postgres_superuser_password
}