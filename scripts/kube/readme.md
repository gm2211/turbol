To login into kube's dashboard you can use the ./start-kube-ui.sh script. To get the login token, use the ./get-kube-secret.sh script.\
If you have issues with the dashboard (i.e. things not displaying properly + warning notifications), run: kubectl apply -f policies


Debug cert-manager:
# Create a ClusterIssuer to test the webhook works okay
cat <<EOF > test-resources.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: cert-manager-test
---
apiVersion: cert-manager.io/v1alpha2
kind: Issuer
metadata:
  name: test-selfsigned
  namespace: cert-manager-test
spec:
  selfSigned: {}
---
apiVersion: cert-manager.io/v1alpha2
kind: Certificate
metadata:
  name: selfsigned-cert
  namespace: cert-manager-test
spec:
  commonName: example.com
  secretName: selfsigned-cert-tls
  issuerRef:
    name: test-selfsigned
EOF

# Create the test resources
kubectl apply -f test-resources.yaml

# Check the status of the newly created certificate
# You may need to wait a few seconds before cert-manager processes the
# certificate request
kubectl describe certificate -n cert-manager-test
...
Spec:
  Common Name:  example.com
  Issuer Ref:
    Name:       test-selfsigned
  Secret Name:  selfsigned-cert-tls
Status:
  Conditions:
    Last Transition Time:  2019-01-29T17:34:30Z
    Message:               Certificate is up to date and has not expired
    Reason:                Ready
    Status:                True
    Type:                  Ready
  Not After:               2019-04-29T17:34:29Z
Events:
  Type    Reason      Age   From          Message
  ----    ------      ----  ----          -------
  Normal  CertIssued  4s    cert-manager  Certificate issued successfully

# Clean up the test resources
kubectl delete -f test-resources.yaml
