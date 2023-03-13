# Providers
provider "kubernetes" {
  host                   = var.k8s_cluster_host
  token                  = var.k8s_cluster_token
  cluster_ca_certificate = var.k8s_cluster_ca
}

module "app-config" {
  source                                = "../app-config"
  app_port                              = var.app_port
  postgres_database_name                = var.postgres_database_name
  postgres_host                         = var.postgres_host
  postgres_port                         = var.postgres_port
  postgres_user                         = var.postgres_user
  postgres_password_env_var_name        = local.postgres_password_env_var_name
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
  server_config_map_data          = {
    (local.install_config_filename) = yamlencode(module.app-config.install_config),
    (local.runtime_config_filename) = yamlencode(module.app-config.runtime_config)
  }
  postgres_password_env_var_name = "POSTGRES_PASSWORD"
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
resource "kubernetes_secret" "postgres-password" {
  metadata {
    name = "postgres-password"
  }
  data = base64encode(var.postgres_password)
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
            limits = {
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
          env {
            name  = "INSTALL_CONFIG_OVERRIDES_PATH"
            value = "${local.server_configs_dir}/${local.install_config_filename}"
          }
          env {
            name  = "RUNTIME_CONFIG_OVERRIDES_PATH"
            value = "${local.server_configs_dir}/${local.runtime_config_filename}"
          }
          env {
            name      = local.postgres_password_env_var_name
            valueFrom = {
              secretKeyRef = {
                name = kubernetes_secret.postgres-password
                key  = username
              }
            }
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
    type = "ClusterIP"
  }
}
