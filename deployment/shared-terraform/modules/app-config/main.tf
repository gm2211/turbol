########## App ##############
# Locals
locals {
  # Install
  install_secrets = {
    vault-access-token = var.vault_root_token // TODO(gm2211): do not use root token, generate a token for this
  }
  install_config  = {
    frontend-assets-path = var.frontend_assets_path
    http-port            = var.app_port
    vault-config         = {
      address = var.vault_address
      secrets = {
        postgres       = {
          path                    = var.vault_postgres_secrets_path
          super-user-password-key = var.vault_postgres_superuser_password_key
        }
        postgres-roles = {
          path            = "${var.vault_postgres_roles_path}/creds"
          admin-role      = var.vault_postgres_admin_role_name
          read-only-role  = var.vault_postgres_read_only_role_name
          read-write-role = var.vault_postgres_read_write_role_name
        }
        oauth2         = {
          path = var.vault_oauth2_path
        }
        authz          = {
          path = var.vault_authz_path
        }
      }
    }
    database-config      = {
      hostname      = var.postgres_host
      port          = var.postgres_port
      database-name = var.postgres_database_name
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
