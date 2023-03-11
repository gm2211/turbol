#!/bin/bash --
terraform import -var 'prod_app_version=latest' module.prod.module.vault.vault_mount.postgres prod-db-roles
terraform import -var 'staging_app_version=latest' module.staging.module.vault.vault_mount.postgres staging-db-roles
