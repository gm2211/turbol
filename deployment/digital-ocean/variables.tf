# Sensitive values - can be set by exporting env variables like TF_VAR_<var_name>
variable digitalocean_api_token {
  sensitive = true
}
variable docker_hub_password {
  sensitive = true
}
variable prod_app_version {
  default = "1.4.0"
}
variable "prod_mapbox_token" {
  sensitive = true
}