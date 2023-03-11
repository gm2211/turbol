# Providers
provider "vault" {
  # This will default to using $VAULT_ADDR if no address is specified
  address = var.vault_address
  token   = var.vault_dev_root_token
}

# Locals
locals {
  // Vault - Secrets
  vault_kv_name        = "secret"
  vault_dev_oauth_path = "${local.vault_kv_name}/dev-oauth"
  vault_dev_authz_path = "${local.vault_kv_name}/dev-authz"
  dev_oauth_secrets    = {
    audience                          = "dev"
    auth0-client-credentials-audience = "https://dev-nxeasd35.us.auth0.com/api/v2/"
    client-id                         = "IAo2985XTvdbeOM6YSQzOYA2P4hKBf0D"
    client-secret                     = "uPZS0e61At31yn74-8yx9aa-9iD1dbYGRuGM7DO0j594Qb3x9sh6TjiqBP78IipT"
    domain                            = "dev-nxeasd35.us.auth0.com"
    logout-redirect-url               = "https://localhost:8051/login"
    redirect-url                      = "https://localhost:8051/auth"
  }

  dev_authz_secrets = {
    enrolled-clinics = [
      {
        clinic-name    = "dev-clinic",
        primary-doctor = {
          first-name = "John",
          last-name  = "Smith",
        }
      }
    ]
  }

  // Vault - Connection
  default_lease_ttl_seconds = 3600
}

resource "vault_generic_secret" "oauth" {
  path      = local.vault_dev_oauth_path
  data_json = jsonencode(local.dev_oauth_secrets)
}

resource "vault_generic_secret" "authz" {
  path      = local.vault_dev_authz_path
  data_json = jsonencode(local.dev_authz_secrets)
}
