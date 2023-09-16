// Providers
provider "digitalocean" {
  token = var.digitalocean_api_token
}
provider "kubernetes" {
  host                   = var.digital_ocean_k8s_host
  token                  = var.digital_ocean_k8s_token
  cluster_ca_certificate = var.digital_ocean_k8s_cluster_ca
  experiments {
    manifest_resource = true
  }
}

// Locals
locals {
  // Certs
  cert_issuer_name        = "letsencrypt-prod"
  cert_issuer_secret_name = "letsencrypt-prod-secret"
  cert_issuer_email       = "turbol@gmeco.cc"
  // LDM - weather server
  ldm_service_name        = "ldm"
  ldm_image_name          = "unidata/ldm-docker:6.13.16"
  ldm_service_port        = 388
  ldm_data_vol_name       = "ldm-data"
  ldm_queue_vol_name      = "ldm-queues"
  ldm_logs_vol_name       = "ldm-logs"
  ldm_cron_vol_name       = "ldm-cron"
  ldm_data_root_vol_name  = "ldm-data-root"
}

// DB
resource "digitalocean_database_cluster" "postgres" {
  name       = "postgres"
  engine     = "pg"
  version    = "15"
  size       = "db-s-1vcpu-1gb"
  region     = "nyc1"
  node_count = 1
}

// External DNS
resource "helm_release" "external-dns" {
  depends_on = [kubernetes_manifest.install-cert-manager-issuer]
  name       = "external-dns"
  chart      = "external-dns"
  repository = var.external_dns_helm_stable_repo
  timeout    = 10 * 60
  # timeout for each k8s action - 10 minutes

  set {
    name  = "rbac.create"
    value = "true"
  }

  set {
    name  = "provider"
    value = "digitalocean"
  }

  set {
    name  = "digitalocean.apiToken"
    value = var.digitalocean_api_token
  }

  set {
    name  = "interval"
    value = "30s"
  }

  set {
    name  = "policy"
    value = "sync"
    # or upsert-only
  }
}

// TLS certs
resource "kubernetes_namespace" "cert-manager" {
  metadata {
    annotations = {
      "certmanager.k8s.io/disable-validation" = "true"
    }
    name = "cert-manager"
  }
}
resource "helm_release" "cert-manager" {
  depends_on = [kubernetes_namespace.cert-manager]
  name       = "cert-manager"
  namespace  = kubernetes_namespace.cert-manager.metadata[0].name
  chart      = "cert-manager"
  version    = "v1.11"
  repository = var.helm_jetstack_repo
  timeout    = 10 * 60
  # timeout for each k8s action - 10 minutes

  set {
    name  = "ingressShim.defaultIssuerName"
    value = local.cert_issuer_name
  }
  set {
    name  = "ingressShim.defaultIssuerKind"
    value = "Issuer"
  }
  set {
    name  = "installCRDs"
    value = "true"
  }
}
resource "kubernetes_secret" "docean-api-token-for-cert-manager" {
  metadata {
    name = "docean-api-token-for-cert-manager"
  }
  type = "kubernetes.io/generic"
  data = {
    access-token = var.digitalocean_api_token
  }
}
resource "null_resource" "clean-up-issuer" {
  provisioner "local-exec" {
    command = "kubectl delete issuer ${local.cert_issuer_name} || true"
  }
}

resource "kubernetes_manifest" "install-cert-manager-issuer" {
  depends_on = [
    helm_release.cert-manager,
    null_resource.clean-up-issuer
  ]
  manifest = {
    "apiVersion" = "cert-manager.io/v1"
    "kind"       = "Issuer"
    "metadata"   = {
      "name"      = local.cert_issuer_name
      "namespace" = "default"
    }
    "spec" = {
      "acme" = {
        "email"               = local.cert_issuer_email
        "privateKeySecretRef" = {
          "name" = local.cert_issuer_secret_name
        }
        "server"  = "https://acme-v02.api.letsencrypt.org/directory"
        "solvers" = [
          {
            dns01 = {
              "digitalocean" = {
                "tokenSecretRef" = {
                  "key"  = "access-token"
                  "name" = kubernetes_secret.docean-api-token-for-cert-manager.metadata[0].name
                }
              }
            }
            selector = {}
          },
        ]
      }
    }
  }
}

// Ingress
resource "helm_release" "nginx-ingress" {
  depends_on = [kubernetes_manifest.install-cert-manager-issuer]
  name       = "nginx"
  version    = "4.5.2"
  chart      = "ingress-nginx"
  repository = var.nginx_helm_stable_repo
  timeout    = 10 * 60
  # timeout for each k8s action - 10 minutes

  set {
    name  = "controller.publishService.enabled"
    value = "true"
  }
}

resource "kubernetes_namespace" "ldm" {
  metadata {
    annotations = {
      name = local.ldm_service_name
    }

    name = local.ldm_service_name
  }
}

resource "kubernetes_deployment" "ldm" {
  metadata {
    name = local.ldm_service_name
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        app = local.ldm_service_name
      }
    }

    template {
      metadata {
        labels = {
          app = local.ldm_service_name
        }
      }

      spec {
        container {
          image = local.ldm_image_name
          name  = local.ldm_service_name

          resources {
            limits = {
              cpu    = "0.5"
              memory = "512Mi"
            }
            requests = {
              cpu    = "250m"
              memory = "50Mi"
            }
          }

          port {
            container_port = local.ldm_service_port
          }

          volume_mount {
            name       = local.ldm_data_vol_name
            mount_path = "/home/ldm/var/data"
          }

          volume_mount {
            name       = local.ldm_queue_vol_name
            mount_path = "/home/ldm/var/queues"
          }

          volume_mount {
            name       = local.ldm_logs_vol_name
            mount_path = "/home/ldm/var/logs"
          }

          volume_mount {
            name       = local.ldm_cron_vol_name
            mount_path = "/var/spool/cron"
          }

          volume_mount {
            name       = local.ldm_data_root_vol_name
            mount_path = "/data"
          }
        }

        volume {
          name = local.ldm_data_vol_name
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim.ldm-data.metadata[0].name
          }
        }

        volume {
          name = local.ldm_queue_vol_name
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim.ldm-queues.metadata[0].name
          }
        }

        volume {
          name = local.ldm_logs_vol_name
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim.ldm-logs.metadata[0].name
          }
        }

        volume {
          name = local.ldm_cron_vol_name
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim.ldm-cron.metadata[0].name
          }
        }

        volume {
          name = local.ldm_data_root_vol_name
          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim.ldm-data-root.metadata[0].name
          }
        }
      }
    }
  }
}

resource "kubernetes_persistent_volume_claim" "ldm-data" {
  metadata {
    name      = "ldm-data"
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = {
        storage = "10Gi"
      }
    }
  }
}

resource "kubernetes_persistent_volume_claim" "ldm-queues" {
  metadata {
    name      = "ldm-queues"
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = {
        storage = "10Gi"
      }
    }
  }
}

resource "kubernetes_persistent_volume_claim" "ldm-logs" {
  metadata {
    name      = "ldm-logs"
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = {
        storage = "10Gi"
      }
    }
  }
}

resource "kubernetes_persistent_volume_claim" "ldm-cron" {
  metadata {
    name      = "ldm-cron"
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = {
        storage = "10Gi"
      }
    }
  }
}

resource "kubernetes_persistent_volume_claim" "ldm-data-root" {
  metadata {
    name      = "ldm-data-root"
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources {
      requests = {
        storage = "10Gi"
      }
    }
  }
}
resource "kubernetes_service" "ldm" {
  metadata {
    name      = local.ldm_service_name
    namespace = kubernetes_namespace.ldm.metadata[0].name
  }

  spec {
    selector = {
      app = local.ldm_service_name
    }

    port {
      port        = local.ldm_service_port
      target_port = local.ldm_service_port
    }

    type = "LoadBalancer"
  }
}