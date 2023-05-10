# Providers
provider "kubernetes" {
  host                   = var.k8s_cluster_host
  token                  = var.k8s_cluster_token
  cluster_ca_certificate = var.k8s_cluster_ca
}

module "app-config" {
  source                 = "../app-config"
  be_app_port            = var.be_app_port
  postgres_database_name = var.postgres_database_name
  postgres_host          = var.postgres_host
  postgres_port          = var.postgres_port
  postgres_user          = var.postgres_user
  postgres_password      = var.postgres_password
  mapbox_token           = var.mapbox_token
}

########## App ##############
# Locals
locals {
  # App
  fe_app_label = "fe-app"
  fe_app_name  = "turbol-fe"

  be_app_label                  = "be-app"
  be_app_name                   = "turbol"
  # Docker
  dockerhub_username_pass       = "${var.dockerhub_username}:${var.dockerhub_password}"
  docker_image_pull_secret_name = kubernetes_secret.docker-hub-login.metadata[0].name
  # Server config
  server_configs_volume_name    = "server-configs"
  server_configs_dir            = "/etc/conf/plain"
  install_config_filename       = "install.yml"
  runtime_config_filename       = "runtime.yml"
  server_config_map_data        = {
    (local.install_config_filename) = yamlencode(module.app-config.install_config),
    (local.runtime_config_filename) = yamlencode(module.app-config.runtime_config)
  }
  server_secrets_volume_name      = "server-secrets"
  server_secrets_dir              = "/etc/conf/secrets"
  server_install_secrets_filename = "install-secrets.yml"
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
    name = "server-config"
  }
  data = local.server_config_map_data
}
resource "kubernetes_secret" "app-server-install-secrets" {
  metadata {
    name = "server-secrets"
  }
  data = local.server_secrets_data
}
resource "kubernetes_deployment" "be-app" {
  metadata {
    name = local.be_app_name
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
        app = local.be_app_label
      }
    }
    template {
      metadata {
        labels = {
          app = local.be_app_label
        }
      }
      spec {
        termination_grace_period_seconds = 30
        image_pull_secrets {
          name = local.docker_image_pull_secret_name
        }
        container {
          image_pull_policy = "Always"
          image             = "${var.dockerhub_username}/${local.be_app_name}:${var.be_app_version}"
          // This is reading the image from the local docker registry
          name              = local.be_app_name
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
            container_port = var.be_app_port
          }
          volume_mount {
            mount_path = local.server_configs_dir
            name       = local.server_configs_volume_name
            read_only  = true
          }
          volume_mount {
            mount_path = local.server_secrets_dir
            name       = local.server_secrets_volume_name
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
            name  = "APP_SECRETS_PATH"
            value = "${local.server_secrets_dir}/${local.server_install_secrets_filename}"
          }
        }
        volume {
          name = local.server_configs_volume_name
          config_map {
            default_mode = "0444" # 0444 is read only
            name         = kubernetes_config_map.app-server-config.metadata[0].name
          }
        }
        volume {
          name = local.server_secrets_volume_name
          secret {
            default_mode = "0444" # 0444 is read only
            secret_name  = kubernetes_secret.app-server-install-secrets.metadata[0].name
          }
        }
      }
    }
  }
}
resource "kubernetes_deployment" "fe-app" {
  metadata {
    name = local.fe_app_name
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
        app = local.fe_app_label
      }
    }
    template {
      metadata {
        labels = {
          app = local.fe_app_label
        }
      }
      spec {
        termination_grace_period_seconds = 30
        image_pull_secrets {
          name = local.docker_image_pull_secret_name
        }
        container {
          image_pull_policy = "Always"
          image             = "${var.dockerhub_username}/${local.fe_app_name}:${var.fe_app_version}"
          // This is reading the image from the local docker registry
          name              = local.fe_app_name
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
            container_port = var.fe_app_port
          }
        }
      }
    }
  }
}
resource "kubernetes_service" "fe-app-service" {
  depends_on = [kubernetes_deployment.fe-app]
  metadata {
    name = local.fe_app_name
  }
  spec {
    selector = {
      app = local.fe_app_label
    }
    port {
      port        = var.fe_app_port
      target_port = var.fe_app_port
    }
    type = "ClusterIP"
  }
}
resource "kubernetes_service" "be-app-service" {
  depends_on = [kubernetes_deployment.be-app]
  metadata {
    name = local.be_app_name
  }
  spec {
    selector = {
      app = local.be_app_label
    }
    port {
      port        = var.be_app_port
      target_port = var.be_app_port
    }
    type = "ClusterIP"
  }
}
