# Sensitive values - can be set by exporting env variables like TF_VAR_<var_name>
variable digitalocean_api_token {
  sensitive = true
}
variable docker_hub_password {
  sensitive = true
}
variable postgres_superuser_password {
  sensitive = true
}
variable prod_app_version {
  default = "1.0.0"
}
variable staging_app_version {
  default = "latest"
}