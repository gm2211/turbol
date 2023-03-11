#!/bin/bash --
#kubectl -n kubernetes-dashboard get -o json `kubectl -n kubernetes-dashboard get secrets -oname | grep default-token` | jq '.data.token'
kubectl -n kube-system describe secrets `kubectl -n kube-system get secret | grep dashboard-token | cut -d " " -f1 -`
