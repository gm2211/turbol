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

resource "null_resource" "ingresses-should-depend-on-this" {
  depends_on = [
    kubernetes_namespace.cert-manager,
    kubernetes_manifest.install-cert-manager-issuer,
    helm_release.nginx-ingress
  ]
}
