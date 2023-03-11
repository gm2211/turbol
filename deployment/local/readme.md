Use ./setup-dev-env.sh to dev locally

If it gets stuck installing nginx-ingress, try `helm repo update`. 

If that doesn't work, try:
```
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update
```
