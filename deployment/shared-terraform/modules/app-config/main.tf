########## App Config ##############
## The only reason why this exist instead of just being part of the app modules is so that we can generate
## local config files when running with local infra
locals {
  // Decided to keep secrets management simple for now - see my other project 'idoctor' for how to use Hashicorp Vault
  install_secrets = {
    postgres-password = var.postgres_password
  }
  # Install
  install_config = {
    server = {
      dev-mode = false
      port     = var.be_app_port
    }
  }
  # Runtime
  runtime_config = {
    logging = {
      root-logger-level   = "info"
      level-by-class-name = {}
    }
    database-config = {
      hostname      = var.postgres_host
      port          = var.postgres_port
      database-name = var.postgres_database_name
      admin-user    = var.postgres_user
    }
  }
}
