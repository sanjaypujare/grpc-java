#!/bin/bash -x

# this script deletes all the TD artifacts created in example-setup.sh

gcloud alpha network-services endpoint-policies delete ecs_mtls_psms --location=global -q

gcloud alpha network-security server-tls-policies delete server_mtls_policy --location=global -q

gcloud compute forwarding-rules delete example-grpc-forwarding-rule --global -q

gcloud compute target-grpc-proxies delete example-grpc-proxy -q

gcloud compute url-maps remove-path-matcher example-grpc-url-map --path-matcher-name example-grpc-path-matcher --global -q

gcloud compute url-maps delete example-grpc-url-map --global -q

gcloud compute backend-services delete example-grpc-service --global -q

gcloud alpha network-security client-tls-policies delete client_mtls_policy --location=global -q

gcloud compute firewall-rules delete fw-allow-health-checks  -q

gcloud compute health-checks delete example-health-check --global -q

# now delete our deployment of service and client
kubectl delete -f ug-example/gke-deployment.yaml
