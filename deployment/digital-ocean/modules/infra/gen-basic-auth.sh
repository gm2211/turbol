#!/usr/bin/env bash --
set -e

eval "$(jq -r '@sh "username=\(.username) password=\(.password)"')"
auth_str=`htpasswd -nb ${username} ${password}`
jq -n --arg auth_value "${auth_str}" '{"auth":$auth_value}'