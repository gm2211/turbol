variable fe_app_version {}
variable fe_app_port {}
variable be_app_version {}
variable be_app_port {}
variable dockerhub_password {}
variable dockerhub_username {}
variable domain {}
variable k8s_cluster_ca {}
variable k8s_cluster_host {}
variable k8s_cluster_token {}
variable postgres_database_name {}
variable postgres_host {}
variable postgres_port {}
variable postgres_user {}
variable postgres_password {}
variable mapbox_token {
  sensitive = true
}
