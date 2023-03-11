external-dns: https://www.digitalocean.com/community/tutorials/how-to-automatically-manage-dns-records-from-digitalocean-kubernetes-using-externaldns
ingress: https://www.digitalocean.com/community/tutorials/how-to-set-up-an-nginx-ingress-on-digitalocean-kubernetes-using-helm

If you want to delete a namespace that's refusing to "die":
1. kubectl get ns $namespace_to_delete -o json | jq '.spec.finalizers=[]' > ns-without-finalizers.json
2. kubectl proxy &
   PID=$!
   curl -X PUT http://localhost:8001/api/v1/namespaces/$namespace_to_delete/finalize -H "Content-Type: application/json" --data-binary @ns-without-finalizers.json
   kill $PID
3. kubectl delete ns $namespace_to_delete
