#!/bin/bash --
grafana_pod=$(kubectl -n loki get pods -oname | grep loki-grafana | sed 's/pod\///g')
token=$(kubectl get secret --namespace loki loki-grafana -o jsonpath="{.data.admin-password}" | base64 --decode)
port=8080
nohup kubectl port-forward $grafana_pod -n loki $port:3000&
echo "Go to http://localhost:$port, username: admin, password: $token"
nohup kubectl port-forward svc/prometheus-operator-grafana -n prometheus-operator $port:80&
