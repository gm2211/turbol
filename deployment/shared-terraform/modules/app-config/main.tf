########## App ##############
# Locals
locals {
  # Install
  install_config = {
    http-port            = var.app_port
    database-config      = {
      hostname              = var.postgres_host
      port                  = var.postgres_port
      database-name         = var.postgres_database_name
      postgres-user         = var.postgres_user
      password_env_var_name = var.postgres_password_env_var_name
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
