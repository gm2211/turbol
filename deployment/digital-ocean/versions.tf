terraform {
  backend "remote" {
    organization = "gm2211"

    workspaces {
      name = "turbol-cli"
    }
  }
  required_providers {
    digitalocean = {
      source = "digitalocean/digitalocean"
    }
    helm = {
      source = "hashicorp/helm"
      version = "~> 2.3.0"
    }
    kubernetes = {
      source = "hashicorp/kubernetes"
    }
  }
  required_version = ">= 1.0.6"
}
