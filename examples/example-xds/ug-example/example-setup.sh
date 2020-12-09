#!/bin/bash -x

# this script creates server side stuff

# first deploy our service
kubectl apply -f ug-example/gke-deployment.yaml

sleep 20s

NEG_NAME=$(gcloud beta compute network-endpoint-groups list | grep example-grpc-server | awk '{print $1}')

if [ x${NEG_NAME} = x ]; then
    echo NEG_NAME is not set, some issue with the deployment ug-steps-files/create-gke-service.yaml. Exiting...
    exit 1
fi

# Give our service account access to TD APIs
gcloud iam service-accounts add-iam-policy-binding \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:meshca-gke-test.svc.id.goog[default/example-grpc-server]" \
  635862331669-compute@developer.gserviceaccount.com

gcloud projects add-iam-policy-binding meshca-gke-test \
   --member "serviceAccount:meshca-gke-test.svc.id.goog[default/example-grpc-server]" \
   --role roles/compute.networkViewer

gcloud compute health-checks create tcp example-health-check --use-serving-port

#create the firewall rule for the health check to work. Note this one needs to be done
#frequently because of GCP keeps deleting the firewall for security reasons
gcloud compute firewall-rules create fw-allow-health-checks --network default --action ALLOW \
    --direction INGRESS \
    --source-ranges 35.191.0.0/16,130.211.0.0/22 \
    --rules tcp

# HTTP2 works but not GRPC pending rollout of cl/344854138
gcloud compute backend-services create example-grpc-service --global \
    --health-checks example-health-check   --load-balancing-scheme INTERNAL_SELF_MANAGED --protocol HTTP2

gcloud compute backend-services add-backend example-grpc-service --global \
       --network-endpoint-group ${NEG_NAME} --network-endpoint-group-zone us-east1-d \
       --balancing-mode RATE     --max-rate-per-endpoint 5

gcloud compute url-maps create example-grpc-url-map --default-service example-grpc-service

gcloud compute url-maps add-path-matcher example-grpc-url-map --default-service  example-grpc-service \
       --path-matcher-name example-grpc-path-matcher \
       --new-hosts example-grpc-server:8000

# TODO: remove once new flow is confirmed with proxyless
#gcloud compute url-maps add-host-rule example-grpc-url-map --hosts example-grpc-server:8000 \
#       --path-matcher-name example-grpc-path-matcher

gcloud compute target-grpc-proxies create example-grpc-proxy --url-map example-grpc-url-map

gcloud compute forwarding-rules create example-grpc-forwarding-rule --global \
  --load-balancing-scheme=INTERNAL_SELF_MANAGED --address=0.0.0.0 \
  --target-grpc-proxy=example-grpc-proxy --ports 8000 \
  --network default

# Create MTLS policy on the server side and attach to an ECS
gcloud alpha network-security server-tls-policies import server_mtls_policy \
  --source=ug-example/server-mtls-policy.yaml --location=global

gcloud alpha network-services endpoint-config-selectors import ecs_mtls_psms \
  --source=ug-example/ecs-mtls-psms.yaml --location=global

# Create MTLS policy on the client side and attach to our backendService
gcloud alpha network-security client-tls-policies import client_mtls_policy \
  --source=ug-example/client-mtls-policy.yaml --location=global

gcloud beta compute backend-services export example-grpc-service --global \
  --destination=/tmp/example-grpc-service.yaml

cat /tmp/example-grpc-service.yaml ug-example/client-security-settings.yaml >/tmp/example-grpc-service1.yaml

gcloud beta compute backend-services import example-grpc-service --global \
  --source=/tmp/example-grpc-service1.yaml -q
