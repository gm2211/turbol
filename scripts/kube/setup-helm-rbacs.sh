#!/bin/bash --
script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

kubectl apply -f $script_dir/policies/k8s-helm-service-account.yml
