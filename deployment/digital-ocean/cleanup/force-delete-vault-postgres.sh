#!/bin/bash --
vault lease revoke -force -prefix staging-db-roles && vault secrets disable staging-db-roles
vault lease revoke -force -prefix prod-db-roles && vault secrets disable prod-db-roles
