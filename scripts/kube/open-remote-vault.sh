#!/bin/bash --
kubectl port-forward $(k get pods -A | grep vault | tr -s ' ' | cut -d" " -f2) 8200:8200&
open http://localhost:8200/ui
