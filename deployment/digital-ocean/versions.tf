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
    kubernetes = {
      source = "hashicorp/kubernetes"
    }
  }
  required_version = ">= 1.4.0"
}
