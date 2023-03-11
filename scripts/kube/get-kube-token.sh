#!/bin/bash --
#namespace=kube-system # no longer installed here
namespace=kubernetes-dashboard
kubectl -n $namespace describe secrets `kubectl -n $namespace get secret | grep dashboard-token | cut -d " " -f1 -`
