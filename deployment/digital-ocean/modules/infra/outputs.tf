output vault_address {
  value = "https://${local.vault_hostname}"
}
output vault_service_name {
  value = local.vault_service_name
}
output vault_service_port {
  value = local.vault_service_port
}
