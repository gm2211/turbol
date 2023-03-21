########## App ##############
# Locals
locals {
  install_secrets = {
    postgres-password = var.postgres_password
  }
  # Install
  install_config = {
    http-port            = var.be_app_port
    database-config      = {
      hostname              = var.postgres_host
      port                  = var.postgres_port
      database-name         = var.postgres_database_name
      postgres-user         = var.postgres_user
    }
  }

  # RUNTIME
  runtime_config = {
    logging = {
      root-logger-level   = "info"
      level-by-class-name = { }
    }
  }
}
