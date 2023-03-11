# Sensitive values - can be set by exporting env variables like TF_VAR_<var_name>
variable docker_hub_password {
  sensitive = true
}
variable k8s_cluster_name {}
