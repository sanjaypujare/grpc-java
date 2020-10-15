#!/bin/bash -x

# this script deletes all the TD artifacts created in td-setup.sh

gcloud alpha network-services endpoint-config-selectors delete ecs_mtls_psms --location=global

gcloud alpha network-security server-tls-policies delete server_mtls_policy --location=global

gcloud compute forwarding-rules delete zatar-grpc-forwarding-rule --global -q

gcloud compute target-http-proxies delete zatar-grpc-proxy --global -q

gcloud compute url-maps remove-host-rule zatar-grpc-url-map --host zatar-grpc-server

gcloud compute url-maps remove-path-matcher zatar-grpc-url-map --path-matcher-name zatar-grpc-path-matcher --global -q

gcloud compute url-maps delete zatar-grpc-url-map --global -q

gcloud compute backend-services delete zatar-grpc-service --global -q

gcloud compute firewall-rules delete fw-allow-health-checks  -q

gcloud compute health-checks delete zatar-test-health-check --global -q
