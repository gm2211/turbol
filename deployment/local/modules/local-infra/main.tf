provider "helm" {
  kubernetes {
    config_context = var.k8s_cluster_name
    config_path    = "~/.kube/config"
  }
}

resource "random_password" "postgres_superuser_password" {
  length           = 25
  special          = true
  override_special = "_%@"
}

locals {
  // Postgres
  postgres_database_name         = "dev"
  postgres_host                  = "postgresql" # postgres is available at `http://postgresql` thanks to kubefwd
  postgres_port                  = 5432
  postgres_superuser             = "postgres" # must be 'postgres' or else it won't have superuser privileges
  postgres_superuser_password    = random_password.postgres_superuser_password.result
  postgres_password_env_var_name = "POSTGRES_PASSWORD"
}

resource "helm_release" "postgresql" {
  name       = "postgresql"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "postgresql"
  version    = "10.12.0"
  timeout    = 10 * 60 # timeout for each k8s action - 10 minutes

  set {
    name  = "global.postgresql.postgresqlDatabase"
    value = local.postgres_database_name
  }

  set {
    name  = "global.postgresql.postgresqlUsername"
    value = local.postgres_superuser
  }

  set {
    name  = "global.postgresql.postgresqlPassword"
    value = local.postgres_superuser_password
  }

  set {
    name  = "service.port"
    value = local.postgres_port
  }
}

resource "local_file" "generated-passwords" {
  filename = "${path.root}/generated-passwords.yml"
  content  = yamlencode({
    postgres-superuser   = local.postgres_superuser
    postgres-password    = local.postgres_superuser_password
  })
}
