#!/bin/bash --

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source "$SCRIPT_DIR"/utils.sh
source "$SCRIPT_DIR/../digital-ocean/secrets"

terraform destroy -target=module.local-infra --auto-approve
terraform destroy --auto-approve
sudo killall kubefwd # kills local port forwarding
sudo pidwait kubefwd || wait_pid kubefwd
sudo killall kubectl # this kills the kube dashboard
rm *.tfstate*
rm -rf .terraform
rm .terraform.lock.hcl
kind delete clusters $KIND_CLUSTER_NAME
