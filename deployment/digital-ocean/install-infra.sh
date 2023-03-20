#!/bin/bash --

#kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.11.0/cert-manager.crds.yaml

if terraform apply --target=module.infra --auto-approve; then 
  exit 0
else 
  echo "If you get error 'Error: rpc error: code = Unknown desc = no matches for cert-manager.io/v1, Resource=Issuer',"
  echo "comment out the 'kubernetes_manifest' resource in modules/infra/main.tf and all other resources that depend on "
  echo "it, run this script again, then uncomment, then re-run this script"
fi
