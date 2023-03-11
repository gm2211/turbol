#!/bin/bash

# Remove local and remote state
rm ${script_dir}/modules/app/terraform.tfstate* || true
rm ${script_dir}/terraform.tfstate* || true
terraform state rm `terraform state list`
