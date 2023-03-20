#!/bin/bash --

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

function kDel() {
  kubectl delete $(kubectl get $1 -o name)
}

# Remove local and remote state
$script_dir/clean-terraform-state.sh

# Cleanup installed k8s apps
helm del `helm list -q` || true

# Cleanup resources
kDel configmaps || true
kDel deployments || true
kDel statefulsets || true
kDel replicasets || true
kDel daemonsets || true
kDel services || true
kDel pods || true
kDel ingress || true
kDel customresourcedefinitions || true
kDel secrets || true

# Cleanup pvcs
kubectl delete $(kubectl get pvc -o name) || true
# Cleanup broken api services (usually cert-manager)
kubectl delete apiservice $(kubectl get apiservice | grep False | cut -d ' ' -f)
# Remove certmanager namespace
kubectl delete namespace cert-manager || true
# Remove certmanager service accounts and roles
kubectl delete $(kubectl get -oname roles | grep nginx)
kubectl delete $(kubectl get -oname rolebindings | grep nginx)
kubectl delete $(kubectl get -oname clusterroles | grep cert-manager)
kubectl delete $(kubectl get -oname clusterroles | grep external-dns)
kubectl delete $(kubectl get -oname clusterroles | grep nginx)
kubectl delete $(kubectl get -oname clusterrolebindings | grep cert-manager)
kubectl delete $(kubectl get -oname clusterrolebindings | grep external-dns)
kubectl delete $(kubectl get -oname clusterrolebindings | grep nginx)
kubectl delete $(kubectl get -oname MutatingWebhookConfiguration | grep cert-manager)
kubectl delete $(kubectl get -oname ValidatingWebhookConfiguration | grep cert-manager)
kubectl -n kube-system delete $(kubectl -n kube-system get -oname configmap | grep cert-manager)
kubectl -n kube-system delete $(kubectl -n kube-system get -oname roles | grep cert-manager)
kubectl -n kube-system delete $(kubectl -n kube-system get -oname rolebindings | grep cert-manager)
kubectl -n kube-system delete $(kubectl -n kube-system get -oname serviceaccounts | grep cert-manager)

## Cleanup secrets and service accounts (can't delete secrets until the owning service account is deleted, so we delete them again here)
kubectl delete serviceaccount $(kubectl get serviceaccount | grep nginx) || true
kubectl delete serviceaccount $(kubectl get serviceaccount | grep external-dns) || true
kDel secrets || true
