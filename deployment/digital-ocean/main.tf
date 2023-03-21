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
  prod_app_name      = "turbol"
  fe_prod_app_name   = "${local.prod_app_name}-fe"
  staging_app_name   = "turbol-staging"
  be_app_port        = 8081
  fe_app_port        = 9000
  // Hostnames
  prod_hostname      = "app.${local.domain}"
  staging_hostname   = "app-staging.${local.domain}"
  // Postgres
  postgres_port      = 25060
  // See: https://cloud.digitalocean.com/databases/postgres?i=0eb48b
  postgres_host      = "private-postgres-do-user-6593469-0.a.db.ondigitalocean.com"
  postgres_superuser = "doadmin"
}

// Modules
module "infra" {
  source                        = "./modules/infra"
  digital_ocean_k8s_cluster_ca  = local.digital_ocean_k8s_cluster_ca
  digital_ocean_k8s_host        = local.digital_ocean_k8s_host
  digital_ocean_k8s_token       = local.digital_ocean_k8s_token
  digitalocean_api_token        = var.digitalocean_api_token
  domain                        = local.domain
  external_dns_helm_stable_repo = "https://charts.bitnami.com/bitnami"
  helm_jetstack_repo            = "https://charts.jetstack.io"
  nginx_helm_stable_repo        = "https://kubernetes.github.io/ingress-nginx"
}

module "prod" {
  source                 = "../shared-terraform/modules/app"
  fe_app_image_name      = local.fe_prod_app_name
  fe_app_name            = local.fe_prod_app_name
  fe_app_port            = local.fe_app_port
  fe_app_version         = var.prod_app_version
  be_app_image_name      = local.prod_app_name
  be_app_name            = local.prod_app_name
  be_app_port            = local.be_app_port
  be_app_version         = var.prod_app_version
  dockerhub_username     = "gm2211"
  dockerhub_password     = var.docker_hub_password
  domain                 = local.domain
  k8s_cluster_ca         = local.digital_ocean_k8s_cluster_ca
  k8s_cluster_host       = local.digital_ocean_k8s_host
  k8s_cluster_token      = local.digital_ocean_k8s_token
  postgres_database_name = "prod"
  postgres_host          = local.postgres_host
  postgres_port          = local.postgres_port
  postgres_user          = local.postgres_superuser
  postgres_password      = var.postgres_superuser_password
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
#}

resource "kubernetes_ingress_v1" "main-ingress" {
  metadata {
    name        = "main-ingress"
    annotations = {
      "kubernetes.io/ingress.class"            = "nginx"
      "certmanager.k8s.io/issuer"              = module.infra.cert_issuer_name
      "certmanager.k8s.io/acme-challenge-type" = "dns01"
      "certmanager.k8s.io/acme-dns01-provider" = "digitalocean"
      "kubernetes.io/ingress.allow-http"       = false
      "kubernetes.io/tls-acme"                 = true
    }
  }

  spec {
    tls {
      hosts = [
        local.prod_hostname,
        local.staging_hostname
      ]
      // Needs to be different than "local.certIssuerSecretName"
      secret_name = "main-ingress-auth-tls"
    }
    rule {
      host = local.prod_hostname
      http {
        path {
          backend {
            service {
              name = local.fe_prod_app_name
              port {
                number = local.fe_app_port
              }
            }
          }
        }
      }
    }
    rule {
      host = local.staging_hostname
      http {
        path {
          backend {
            service {
              name = local.staging_app_name
              port {
                number = local.be_app_port
              }
            }
          }
        }
      }
    }
  }
}
