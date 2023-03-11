#!/bin/bash --

port=8080
nohup kubectl port-forward svc/prometheus-operator-grafana -n prometheus-operator $port:80&
echo "Go to http://localhost:$port, username: admin, password: prom-operator"
