#!/bin/bash --
kubectl get ns cert-manager -o yaml > /tmp/ns-cert-manager
yq w /tmp/ns-cert-manager "spec.finalizers" [] > /tmp/ns-cert-manager-patched
yq r -j /tmp/ns-cert-manager-patched > /tmp/ns-cert-manager-patched.json

TOKEN="$(kubectl -n kube-system describe secret $(kubectl -n kube-system get secrets | grep helm | cut -f1 -d ' ') | grep -E '^token' | cut -f2 -d':' | tr -d '\t' | tr -d ' ')"
curl -X PUT --data-binary @/tmp/ns-cert-manager-patched.json http://localhost:8001/api/v1/namespaces/cert-manager/finalize -H "Content-Type: application/json" --header "Authorization: Bearer $TOKEN" --insecure

