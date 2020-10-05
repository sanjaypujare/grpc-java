#!/bin/bash -x

# this script creates all the TD artifacts needed for us

# first deploy our service and client
kubectl apply -f zatar-test/zatar-gke-service.yaml

NEG_NAME=$(gcloud beta compute network-endpoint-groups list | grep zatar-grpc-server | awk '{print $1}')

if [ x${NEG_NAME} = x ]; then
    echo NEG_NAME is not set, some issue with the deployment zatar-test/zatar-gke-service.yaml. Exiting...
    exit 1
fi

# Give our service account access to TD APIs
gcloud iam service-accounts add-iam-policy-binding \
  --role roles/iam.workloadIdentityUser \
  --member "serviceAccount:meshca-gke-test.svc.id.goog[zatar-grpc-server/zatar-grpc-server]" \
  635862331669-compute@developer.gserviceaccount.com

gcloud compute health-checks create tcp zatar-test-health-check --port 8000

#create the firewall rule for the health check to work. Note this one needs to be done
#frequently because of GCP keeps deleting the firewall for security reasons
gcloud compute firewall-rules create fw-allow-health-checks --network default --action ALLOW \
    --direction INGRESS \
    --source-ranges 35.191.0.0/16,130.211.0.0/22 \
    --rules tcp

# TODO: should the protocol be HTTP2 or GRPC?
gcloud compute backend-services create zatar-grpc-service --global \
    --health-checks zatar-test-health-check   --load-balancing-scheme INTERNAL_SELF_MANAGED --protocol HTTP2

gcloud compute backend-services add-backend zatar-grpc-service --global \
       --network-endpoint-group ${NEG_NAME} --network-endpoint-group-zone us-west1-b \
       --balancing-mode RATE     --max-rate-per-endpoint 5

gcloud compute url-maps create zatar-grpc-url-map --default-service zatar-grpc-service

gcloud compute url-maps add-path-matcher zatar-grpc-url-map --default-service  zatar-grpc-service \
       --path-matcher-name zatar-grpc-path-matcher

gcloud compute url-maps add-host-rule zatar-grpc-url-map --hosts zatar-grpc-server \
       --path-matcher-name zatar-grpc-path-matcher

gcloud compute target-http-proxies create zatar-grpc-proxy --url-map zatar-grpc-url-map

gcloud compute forwarding-rules create zatar-grpc-forwarding-rule --global \
  --load-balancing-scheme=INTERNAL_SELF_MANAGED --address=0.0.0.0 \
  --target-http-proxy=zatar-grpc-proxy --ports 8000 \
  --network default

echo now enter the zatar-grpc-server pod shell and run the server as follows:
echo /build/install/example-tls/bin/hello-world-xds-server 8000

