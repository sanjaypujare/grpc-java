#!/bin/bash -x

export PROJECT=`gcloud config get-value project`
export PROJNUM=`gcloud projects describe $PROJECT --format="value(projectNumber)"`
KUBECTL_CONFIG=`kubectl config get-clusters |grep $PROJECT`
NUM_CONFIGS=`echo $KUBECTL_CONFIG |wc -w`

if (($NUM_CONFIGS>1)); then
  echo Found $NUM_CONFIGS configs... exiting
  exit 1
fi

kubectl config set-context $KUBECTL_CONFIG
kubectl config use-context $KUBECTL_CONFIG

# this script creates server side stuff
CLUSTER_ZONE=`echo $KUBECTL_CONFIG | cut -d'_' -f 3`

# first deploy our service
cat ug-example/gke-deployment.yaml | envsubst | kubectl apply -f -

sleep 20s

NEG_NAME=$(gcloud beta compute network-endpoint-groups list | grep example-grpc-server | grep ${CLUSTER_ZONE} | awk '{print $1}')

if [ x${NEG_NAME} != xexample-grpc-server ]; then
    echo NEG_NAME is not set, some issue with the deployment ug-example/gke-deployment.yaml. Exiting...
    exit 1
fi

# Give our service accounts access to TD APIs
gcloud iam service-accounts add-iam-policy-binding \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:${PROJECT}.svc.id.goog[default/example-grpc-server]" \
  ${PROJNUM}-compute@developer.gserviceaccount.com

gcloud projects add-iam-policy-binding ${PROJECT} \
   --member "serviceAccount:${PROJECT}.svc.id.goog[default/example-grpc-server]" \
   --role roles/compute.networkViewer

gcloud iam service-accounts add-iam-policy-binding \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:${PROJECT}.svc.id.goog[default/example-grpc-client]" \
  ${PROJNUM}-compute@developer.gserviceaccount.com

gcloud projects add-iam-policy-binding ${PROJECT} \
   --member "serviceAccount:${PROJECT}.svc.id.goog[default/example-grpc-client]" \
   --role roles/compute.networkViewer

gcloud compute health-checks create tcp example-health-check --enable-logging --use-serving-port

#create the firewall rule for the health check to work. Note this one needs to be done
#frequently because of GCP keeps deleting the firewall for security reasons
gcloud compute firewall-rules create fw-allow-health-checks --network default --action ALLOW \
    --direction INGRESS \
    --source-ranges 35.191.0.0/16,130.211.0.0/22 \
    --rules tcp

gcloud compute backend-services create example-grpc-service --global \
    --health-checks example-health-check   --load-balancing-scheme INTERNAL_SELF_MANAGED --protocol GRPC

gcloud compute backend-services add-backend example-grpc-service --global \
       --network-endpoint-group example-grpc-server --network-endpoint-group-zone ${CLUSTER_ZONE} \
       --balancing-mode RATE     --max-rate-per-endpoint 5

# Create MTLS policy on the server side and attach to an ECS
gcloud beta network-security server-tls-policies import server_mtls_policy \
  --source=ug-example/server-mtls-policy.yaml --location=global

gcloud alpha network-services endpoint-policies import ecs_mtls_psms \
  --source=ug-example/ecs-mtls-psms.yaml --location=global

# Create MTLS policy on the client side and attach to our backendService
gcloud beta network-security client-tls-policies import client_mtls_policy \
  --source=ug-example/client-mtls-policy.yaml --location=global

gcloud compute backend-services export example-grpc-service --global \
  --destination=/tmp/example-grpc-service.yaml

cat /tmp/example-grpc-service.yaml ug-example/client-security-settings.yaml | envsubst >/tmp/example-grpc-service1.yaml

gcloud compute backend-services import example-grpc-service --global \
  --source=/tmp/example-grpc-service1.yaml -q

# finish the remaining routing/LB steps
sleep 20s
gcloud compute url-maps create example-grpc-url-map --default-service example-grpc-service

gcloud compute url-maps add-path-matcher example-grpc-url-map --default-service  example-grpc-service \
       --path-matcher-name example-grpc-path-matcher \
       --new-hosts example-grpc-server:8000

gcloud compute target-grpc-proxies create example-grpc-proxy --url-map example-grpc-url-map \
       --validate-for-proxyless

gcloud compute forwarding-rules create example-grpc-forwarding-rule --global \
  --load-balancing-scheme=INTERNAL_SELF_MANAGED --address=0.0.0.0 \
  --target-grpc-proxy=example-grpc-proxy --ports 8000 \
  --network default
