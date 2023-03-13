########## App ##############
# Locals
locals {
  # Install
  install_config = {
    frontend-assets-path = var.frontend_assets_path
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
      level-by-class-name = {
        "com.gm2211.turbol.backend.authentication.oauth.Auth0OAuthManager" = "debug"
        "com.gm2211.turbol.backend.secrets.VaultBasedSecretsRegistry"      = "debug"
      }
    }
  }
}
