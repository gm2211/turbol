variable be_app_port {}
variable be_dev_mode {
  default = false
}
variable postgres_host {}
variable postgres_port {}
variable postgres_user {}
variable postgres_password {}
variable postgres_database_name {}
variable mapbox_token {
  sensitive = true
}
