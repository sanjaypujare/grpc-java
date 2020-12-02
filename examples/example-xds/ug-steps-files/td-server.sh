#!/bin/bash -x

# this script creates server side stuff

# first deploy our service
kubectl apply -f ug-steps-files/create-gke-service.yaml

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

# Create MTLS policy on the server side and attach to an ECS
gcloud alpha network-security server-tls-policies import server_mtls_policy \
  --source=ug-steps-files/server-mtls-policy.yaml --location=global

gcloud alpha network-services endpoint-config-selectors import ecs_mtls_psms \
  --source=ug-steps-files/ecs-mtls-psms.yaml --location=global
