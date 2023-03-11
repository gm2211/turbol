# Providers
provider "kubernetes" {
  host                   = var.k8s_cluster_host
  token                  = var.k8s_cluster_token
  cluster_ca_certificate = var.k8s_cluster_ca
}

module "app-config" {
  source = "../app-config"
  app_port = var.app_port
  postgres_database_name = var.postgres_database_name
  postgres_host = var.postgres_host
  postgres_port = var.postgres_port
  vault_address = var.vault_address
  vault_authz_path = var.vault_authz_path
  vault_oauth2_path = var.vault_oauth2_path
  vault_postgres_admin_role_name = var.vault_postgres_admin_role_name
  vault_postgres_read_only_role_name =  var.vault_postgres_read_only_role_name
  vault_postgres_read_write_role_name = var.vault_postgres_read_write_role_name
  vault_postgres_roles_path = var.vault_postgres_roles_path
  vault_postgres_secrets_path = var.vault_postgres_secrets_path
  vault_postgres_superuser_password_key = var.vault_postgres_superuser_password_key
  vault_root_token = var.vault_root_token
}
########## App ##############
# Locals
locals {
  # Network
  app_label                       = var.app_name
  # Docker
  dockerhub_username_pass         = "${var.dockerhub_username}:${var.dockerhub_password}"
  docker_image_pull_secret_name   = kubernetes_secret.docker-hub-login.metadata[0].name
  # Server config
  install_config_filename         = "install.yml"
  runtime_config_filename         = "runtime.yml"
  server_install_secrets_filename = "install-secrets.yml"
  server_configs_dir              = "/etc/conf/plain"
  server_secrets_dir              = "/etc/conf/secrets"
  server_config_map_data          = {
    (local.install_config_filename) = yamlencode(module.app-config.install_config),
    (local.runtime_config_filename) = yamlencode(module.app-config.runtime_config)
  }
  server_secrets_data             = {
    (local.server_install_secrets_filename) = yamlencode(module.app-config.install_secrets)
  }
}
resource "kubernetes_secret" "docker-hub-login" {
  metadata {
    name = "docker-hub-login"
  }
  data = {
    ".dockerconfigjson" = <<EOF
{
	"auths": {
		"https://index.docker.io/v1/": {
			"auth": "${base64encode(local.dockerhub_username_pass)}"
		}
	},
	"HttpHeaders": {
		"User-Agent": "Docker-Client/19.03.5 (darwin)"
	}
}
EOF
  }
  type = "kubernetes.io/dockerconfigjson"
}
resource "kubernetes_config_map" "app-server-config" {
  metadata {
    name = "${var.app_name}-server-config"
  }
  data = local.server_config_map_data
}
resource "kubernetes_secret" "app-server-install-secrets" {
  metadata {
    name = "${var.app_name}-install-secrets"
  }
  data = local.server_secrets_data
}
resource "kubernetes_deployment" "app" {
  metadata {
    name = var.app_name
  }
  spec {
    revision_history_limit = 1
    replicas               = 1
    strategy {
      type = "RollingUpdate"
      rolling_update {
        max_surge       = 1
        max_unavailable = 0
      }
    }
    selector {
      match_labels = {
        app = local.app_label
      }
    }
    template {
      metadata {
        name   = var.app_name
        labels = {
          app = local.app_label
        }
      }
      spec {
        termination_grace_period_seconds = 30
        image_pull_secrets {
          name = local.docker_image_pull_secret_name
        }
        container {
          image_pull_policy = "Always"
          image             = "${var.dockerhub_username}/${var.app_image_name}:${var.app_version}"
          // This is reading the image from the local docker registry
          name              = var.app_name
          resources {
            requests = {
              cpu    = "0.1"
              memory = "500Mi"
            }
            limits   = {
              cpu    = "0.3"
              memory = "1Gi"
            }
          }
          port {
            container_port = var.app_port
          }
          volume_mount {
            mount_path = local.server_configs_dir
            name       = "server-config"
            read_only  = true
          }
          volume_mount {
            mount_path = local.server_secrets_dir
            name       = "server-install-secrets"
            read_only  = true
          }
          env {
            name  = "INSTALL_CONFIG_OVERRIDES_PATH"
            value = "${local.server_configs_dir}/${local.install_config_filename}"
          }
          env {
            name  = "RUNTIME_CONFIG_OVERRIDES_PATH"
            value = "${local.server_configs_dir}/${local.runtime_config_filename}"
          }
          env {
            name  = "INSTALL_SECRETS_PATH"
            value = "${local.server_secrets_dir}/${local.server_install_secrets_filename}"
          }
        }
        volume {
          name = "server-config"
          config_map {
            default_mode = "0444"
            // readonly
            name         = kubernetes_config_map.app-server-config.metadata[0].name
          }
        }
        volume {
          name = "server-install-secrets"
          secret {
            default_mode = "0444"
            // readonly
            secret_name  = kubernetes_secret.app-server-install-secrets.metadata[0].name
          }
        }
      }
    }
  }
}
resource "kubernetes_service" "app-service" {
  depends_on = [kubernetes_deployment.app]
  metadata {
    name = var.app_name
  }
  spec {
    selector = {
      app = local.app_label
    }
    port {
      port        = var.app_port
      target_port = var.app_port
    }
    type     = "ClusterIP"
  }
}
