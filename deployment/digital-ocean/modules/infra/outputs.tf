output "cert_issuer_name" {
  value = local.cert_issuer_name
}
output "postgres_cluster_id" {
  value = digitalocean_database_cluster.postgres.id
}
output "postgres_host" {
  value = digitalocean_database_cluster.postgres.host
}
output "postgres_admin_user" {
  value = digitalocean_database_cluster.postgres.user
}
output "postgres_port" {
  value = digitalocean_database_cluster.postgres.port
}
output "postgres_password" {
  value = digitalocean_database_cluster.postgres.password
}
