#!/bin/bash --

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$SCRIPT_DIR/utils.sh"

terraform import module.populate-vault-db-roles.vault_mount.postgres dev-db-roles
