#!/bin/bash --

function genToken() {
    openssl rand -base64 $1 | gsed "s/[^[:alpha:][:digit:]]*//g"
}

if [[ -z "${VAULT_ADDR}" ]]; then
    echo "Enter vault address: "
    read VAULT_ADDR
    export VAULT_ADDR
else
    echo "Using $VAULT_ADD as vault address - make sure it\'s correct - can check by using kubernetes dashboard"
fi

# Generating postgres password (randomly)
vault secret put "secret/prod-db-secrets" "postgres-admin-password"=`genToken 32` --address="$VAULT_ADDR"
vault secret put "secret/staging-db-secrets" "postgres-admin-password"=`genToken 32` --address="$VAULT_ADDR"
