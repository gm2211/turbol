terraform {
  required_providers {
    digitalocean = {
      source = "digitalocean/digitalocean"
    }
    helm = {
      source  = "hashicorp/helm"
    }
    kubernetes = {
      source = "hashicorp/kubernetes"
    }
  }
}
