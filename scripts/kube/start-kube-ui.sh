#!/bin/bash --

kubectl apply -f "https://raw.githubusercontent.com/kubernetes/dashboard/v2.3.1/aio/deploy/recommended.yaml"
kubectl apply -f "https://gist.githubusercontent.com/gm2211/ff6aa84a29b7ea8d0b1d8965bd15b05e/raw/ce123093adca15e484b4ab03e2eac2bf78022f16/k8s-dash-cluster-role.yml"
echo $(kubetoken)
cur_dir=$(pwd)
cd /tmp
nohup kubectl proxy&
cd $pwd
dash_url="http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/"
echo "Opening $dash_url"
open $dash_url || xdg-open $dash_url