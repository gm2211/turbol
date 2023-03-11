#!/bin/bash --
script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

# Install dashboard
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.4.0/aio/deploy/recommended.yaml
# Setup rbacs - also available at https://gist.githubusercontent.com/gm2211/ff6aa84a29b7ea8d0b1d8965bd15b05e/raw/ce123093adca15e484b4ab03e2eac2bf78022f16/k8s-dash-cluster-role.yml
kubectl apply -f "$script_dir"/policies/k8s-dash-cluster-role.yml
