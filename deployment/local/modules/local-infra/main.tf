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
}

resource "helm_release" "postgresql" {
  name       = "postgresql"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "postgresql"
  version    = "12.2.7"
  timeout    = 10 * 60 # timeout for each k8s action - 10 minutes

    set {
      name  = "global.postgresql.auth.postgresPassword"
      value = local.postgres_superuser_password
    }

    set {
      name  = "global.postgresql.service.ports.postgresql"
      value = local.postgres_port
    }
}
resource "null_resource" "delete_pvs" {
  depends_on = [helm_release.postgresql]

  provisioner "local-exec" {
    when    = destroy
    command = "kubectl delete pv postgresql-0"
  }
}

resource "local_file" "generated-passwords" {
  filename = "${path.root}/generated-passwords.yml"
  content  = yamlencode({
    postgres-superuser   = local.postgres_superuser
    postgres-password    = local.postgres_superuser_password
  })
}
