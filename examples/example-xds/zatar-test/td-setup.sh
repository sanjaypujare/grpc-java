#!/bin/bash -x

# this script creates all the TD artifacts needed for us

# first deploy our service and client
kubectl apply -f zatar-test/zatar-gke-service.yaml

sleep 20s

NEG_NAME=$(/google/data/ro/teams/cloud-sdk/gcloud beta compute network-endpoint-groups list | grep zatar-grpc-server | awk '{print $1}')

if [ x${NEG_NAME} = x ]; then
    echo NEG_NAME is not set, some issue with the deployment zatar-test/zatar-gke-service.yaml. Exiting...
    exit 1
fi

# Give our service account access to TD APIs
/google/data/ro/teams/cloud-sdk/gcloud iam service-accounts add-iam-policy-binding \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:bct-staging-td-gke-consumer.svc.id.goog[zatar-grpc-server/zatar-grpc-server]" \
  247312067043-compute@developer.gserviceaccount.com

/google/data/ro/teams/cloud-sdk/gcloud iam service-accounts add-iam-policy-binding \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:bct-staging-td-gke-consumer.svc.id.goog[zatar-grpc-client/zatar-grpc-client]" \
  247312067043-compute@developer.gserviceaccount.com

/google/data/ro/teams/cloud-sdk/gcloud projects add-iam-policy-binding meshca-gke-test \
   --member "serviceAccount:bct-staging-td-gke-consumer.svc.id.goog[zatar-grpc-server/zatar-grpc-server]" \
   --role roles/compute.networkViewer

/google/data/ro/teams/cloud-sdk/gcloud projects add-iam-policy-binding meshca-gke-test \
   --member "serviceAccount:bct-staging-td-gke-consumer.svc.id.goog[zatar-grpc-client/zatar-grpc-client]" \
   --role roles/compute.networkViewer

/google/data/ro/teams/cloud-sdk/gcloud compute health-checks create tcp zatar-test-health-check --port 8000

#create the firewall rule for the health check to work. Note this one needs to be done
#frequently because of GCP keeps deleting the firewall for security reasons
/google/data/ro/teams/cloud-sdk/gcloud compute firewall-rules create fw-allow-health-checks --network default --action ALLOW \
    --direction INGRESS \
    --source-ranges 35.191.0.0/16,130.211.0.0/22,108.170.220.0/24 \
    --rules tcp

# HTTP2 works but not GRPC...
/google/data/ro/teams/cloud-sdk/gcloud compute backend-services create zatar-grpc-service --global \
    --health-checks zatar-test-health-check   --load-balancing-scheme INTERNAL_SELF_MANAGED --protocol HTTP2

/google/data/ro/teams/cloud-sdk/gcloud compute backend-services add-backend zatar-grpc-service --global \
       --network-endpoint-group ${NEG_NAME} --network-endpoint-group-zone us-west1-pj1 \
       --balancing-mode RATE     --max-rate-per-endpoint 5

/google/data/ro/teams/cloud-sdk/gcloud compute url-maps create zatar-grpc-url-map --default-service zatar-grpc-service

/google/data/ro/teams/cloud-sdk/gcloud compute url-maps add-path-matcher zatar-grpc-url-map --default-service  zatar-grpc-service \
       --path-matcher-name zatar-grpc-path-matcher

/google/data/ro/teams/cloud-sdk/gcloud compute url-maps add-host-rule zatar-grpc-url-map --hosts zatar-grpc-server:8000 \
       --path-matcher-name zatar-grpc-path-matcher

/google/data/ro/teams/cloud-sdk/gcloud compute target-grpc-proxies create zatar-grpc-proxy --url-map zatar-grpc-url-map

/google/data/ro/teams/cloud-sdk/gcloud compute forwarding-rules create zatar-grpc-forwarding-rule --global \
  --load-balancing-scheme=INTERNAL_SELF_MANAGED --address=0.0.0.0 \
  --target-grpc-proxy=zatar-grpc-proxy --ports 8000 \
  --network default

# Create MTLS policy on the server side and attach to an ECS
/google/data/ro/teams/cloud-sdk/gcloud alpha network-security server-tls-policies import server_mtls_policy \
  --source=zatar-test/server-mtls-policy.yaml --location=global

/google/data/ro/teams/cloud-sdk/gcloud alpha network-services endpoint-config-selectors import ecs_mtls_psms \
  --source=zatar-test/ecs-mtls-psms.yaml --location=global

# Create MTLS policy on the client side and attach to our backendService
/google/data/ro/teams/cloud-sdk/gcloud alpha network-security client-tls-policies import client_mtls_policy \
  --source=zatar-test/client-mtls-policy.yaml --location=global

/google/data/ro/teams/cloud-sdk/gcloud beta compute backend-services export zatar-grpc-service --global \
  --destination=/tmp/zatar-grpc-service.yaml

cat /tmp/zatar-grpc-service.yaml zatar-test/client-security-settings.yaml >/tmp/zatar-grpc-service1.yaml

/google/data/ro/teams/cloud-sdk/gcloud beta compute backend-services import zatar-grpc-service --global \
  --source=/tmp/zatar-grpc-service1.yaml -q

echo Now enter the zatar-grpc-server pod shell and run the server as follows:
echo /build/install/example-xds/bin/xds-hello-world-server 8000 my-test-xds-server --secure

echo Once server is running, enter zatar-grpc-client pod shell and run client:
echo /build/install/example-xds/bin/xds-hello-world-client my-xds-client xds:///zatar-grpc-server:8000 --secure
