#!/bin/bash -x

PROJECT=`gcloud config get-value project`
CLUSTER_NAME=zatar-apr15
CLUSTER_ZONE=us-west1-b

# To create a cluster with workload identity and managed SPIFFE certs enabled
# note we had performed the following override:
#gcloud config set api_endpoint_overrides/container https://test-container.sandbox.googleapis.com/
gcloud beta container clusters create ${CLUSTER_NAME} --cluster-version=1.20.5-gke.1300 \
  --release-channel=rapid --workload-pool=${PROJECT}.svc.id.goog \
  --enable-workload-certificates --zone=${CLUSTER_ZONE} \
  --scopes=cloud-platform  --enable-ip-alias \
  --tags=allow-health-checks --workload-metadata=GCE_METADATA

gcloud container clusters get-credentials ${CLUSTER_NAME} --zone=${CLUSTER_ZONE}

# To update a cluster to add workload identity and managed SPIFFE certs. Note that the cluster must be version 1.18 or above in the rapid channel.
#gcloud alpha container clusters update existing-cluster \
#  --workload-pool={project-id}.svc.id.goog \
#--workload-identity-certificate-authority=//privateca.googleapis.com/projects/{project}/locations/{location}/certificateAuthorities/{name} \
#  --impersonate-service-account=testing-sa@{project}.iam.gserviceaccount.com \
#  --zone={location}
