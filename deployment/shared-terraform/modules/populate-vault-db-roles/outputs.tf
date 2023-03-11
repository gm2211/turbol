output vault_kv_name {
  value = var.vault_kv_name
}
output postgres_admin_role_name {
  value = local.admin_role_name
}
output postgres_read_only_role_name {
  value = local.read_only_role_name
}
output postgres_read_write_role_name {
  value = local.read_write_role_name
}
output postgres_secrets_path {
  value = local.db_secrets_path
}
output postgres_roles_path {
  value = local.db_roles_path
}
output postgres_superuser_password_key {
  value = local.postgres_superuser_password_key
}
