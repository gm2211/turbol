# Providers
provider "vault" {
  # This will default to using $VAULT_ADDR if no address is specified
  address = var.vault_address
  token   = var.vault_root_token
}

/*
VAULT SCHEMA
------------

secret/prod-db-secrets:
  postgres-admin-password: ..
secret/prod-oauth:
  audience: ..
  auth0-client-credentials-audience: ..
  client-id: ..
  client-secret: ..
  domain: ..
  logout-redirect-url: ..
  redirect-url: ..
secret/prod-authz:
  configs-by-clinic: {"staging": { "primary-doctor": { "first": "John", "last": "Smith"}}}
secret/staging-db-secrets:
  postgres-admin-password
secret/staging-oauth:
  audience: ..
  auth0-client-credentials-audience: ..
  client-id: ..
  client-secret: ..
  domain: ..
  logout-redirect-url: ..
  redirect-url: ..
secret/staging-authz:
  enrolled-clinics: ..
*/
# Locals
locals {
  // Vault
  default_lease_ttl_seconds = 3600

  // Postgres
  postgres_url = "${var.postgres_hostname}:${var.postgres_port}"

  // Vault - Postgres
  db_secrets_path                 = "${var.vault_kv_name}/${var.environment_name}-db-secrets"
  db_roles_path                   = "${var.environment_name}-db-roles"
  admin_role_name                 = "admin"
  read_write_role_name            = "read-only"
  read_only_role_name             = "read-write"
  postgres_superuser_password_key = "admin-password"
  postgres_vault_secrets          = {
    (local.postgres_superuser_password_key) = var.postgres_superuser_password
  }
}

# Unnecessary, but this way services can login as admin if need be (by reading the password from vault)
resource "vault_generic_secret" "database-secrets" {
  path      = local.db_secrets_path
  data_json = jsonencode(local.postgres_vault_secrets)
}
resource "vault_mount" "postgres" {
  path                      = local.db_roles_path
  type                      = "database"
  default_lease_ttl_seconds = local.default_lease_ttl_seconds
  max_lease_ttl_seconds     = local.default_lease_ttl_seconds
}
# Setup Database roles
resource "vault_database_secret_backend_connection" "postgres" {
  backend       = vault_mount.postgres.path
  name          = var.postgres_database_name
  allowed_roles = [
    local.admin_role_name,
    local.read_only_role_name,
    local.read_write_role_name
  ]
  postgresql {
    connection_url = "postgres://${var.postgres_superuser}:${var.postgres_superuser_password}@${local.postgres_url}/${var.postgres_database_name}${var.postgres_connection_args}"
  }
}
locals {
  admin_create_statements      = [
    "DO $$ BEGIN CREATE ROLE \"${local.admin_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; EXCEPTION WHEN DUPLICATE_OBJECT THEN RAISE NOTICE 'role already exists'; END $$",
    "ALTER ROLE \"${local.admin_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';",
    "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"${local.admin_role_name}\";",
    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO \"${local.admin_role_name}\";"
  ]
  read_write_create_statements = [
    "DO $$ BEGIN CREATE ROLE \"${local.read_write_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; EXCEPTION WHEN DUPLICATE_OBJECT THEN RAISE NOTICE 'role already exists'; END $$",
    "ALTER ROLE \"${local.read_write_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';",
    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO \"${local.read_write_role_name}\";",
    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO \"${local.read_write_role_name}\";"
  ]
  read_only_create_statements  = [
    "DO $$ BEGIN CREATE ROLE \"${local.read_only_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; EXCEPTION WHEN DUPLICATE_OBJECT THEN RAISE NOTICE 'role already exists'; END $$",
    "ALTER ROLE \"${local.read_only_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';",
    "GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"${local.read_only_role_name}\";",
    "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO \"${local.read_only_role_name}\";"
  ]
}
resource "vault_database_secret_backend_role" "admin-role" {
  backend               = vault_mount.postgres.path
  name                  = local.admin_role_name
  db_name               = vault_database_secret_backend_connection.postgres.name
  default_ttl           = local.default_lease_ttl_seconds
  max_ttl               = local.default_lease_ttl_seconds
  revocation_statements = [
    "REASSIGN OWNED BY \"${local.admin_role_name}\" to \"${var.postgres_superuser}\";",
    "DROP ROLE \"${local.admin_role_name}\";"
  ]
  renew_statements      = [
    "ALTER ROLE \"${local.admin_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';",
  ]
  creation_statements   = [
    base64encode(jsonencode(local.admin_create_statements))
  ]
}
resource "vault_database_secret_backend_role" "read-write-role" {
  backend               = vault_mount.postgres.path
  name                  = local.read_write_role_name
  db_name               = vault_database_secret_backend_connection.postgres.name
  default_ttl           = local.default_lease_ttl_seconds
  max_ttl               = local.default_lease_ttl_seconds
  revocation_statements = [
    "REASSIGN OWNED BY \"${local.read_write_role_name}\" to \"${var.postgres_superuser}\";",
    "DROP ROLE \"${local.read_write_role_name}\";"
  ]
  renew_statements      = [
    "ALTER ROLE \"${local.read_write_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';",
  ]
  creation_statements   = [
    base64encode(jsonencode(local.read_write_create_statements))
  ]
}
resource "vault_database_secret_backend_role" "read-only-role" {
  backend               = vault_mount.postgres.path
  name                  = local.read_only_role_name
  db_name               = vault_database_secret_backend_connection.postgres.name
  default_ttl           = local.default_lease_ttl_seconds
  max_ttl               = local.default_lease_ttl_seconds
  revocation_statements = [
    "REASSIGN OWNED BY \"${local.read_only_role_name}\" to \"${var.postgres_superuser}\";",
    "DROP ROLE \"${local.read_only_role_name}\";"
  ]
  renew_statements      = [
    "ALTER ROLE \"${local.read_only_role_name}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}';",
  ]
  creation_statements   = [
    base64encode(jsonencode(local.read_only_create_statements))
  ]
}
