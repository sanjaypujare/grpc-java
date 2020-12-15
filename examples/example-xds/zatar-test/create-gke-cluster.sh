#!/bin/bash -x

PROJECT=bct-staging-proxyless-grpc
CLUSTER_NAME=bct-staging-grpc-dec15
CLUSTER_ZONE=europe-west1-newvmwo
# old zone us-west1-pj1
CA_NAME=pkcs2-ca-staging-nov30
CA_ZONE=us-central1
#CA_URL=//privateca.googleapis.com/projects/${PROJECT}/locations/${CA_ZONE}/certificateAuthorities/${CA_NAME}
CA_URL=//privateca.googleapis.com/projects/bct-staging-proxyless-grpc/locations/europe-west1/certificateAuthorities/interop-psm-security-ca2

# To create a cluster with workload identity and managed SPIFFE certs enabled
/google/data/ro/teams/cloud-sdk/gcloud beta container clusters create ${CLUSTER_NAME} \
  --cluster-version=1.18.12-gke.1200 --image-type=cos_containerd   --release-channel=rapid \
  --workload-pool=${PROJECT}.svc.id.goog --workload-identity-certificate-authority=${CA_URL} \
  --zone=${CLUSTER_ZONE} --scopes=cloud-platform   --enable-ip-alias --workload-metadata=GCE_METADATA \
  --num-nodes=1

#/google/data/ro/teams/cloud-sdk/gcloud container clusters get-credentials ${CLUSTER_NAME} --zone=${CLUSTER_ZONE}

# To update a cluster to add workload identity and managed SPIFFE certs. Note that the cluster must be version 1.18 or above in the rapid channel.
#gcloud alpha container clusters update existing-cluster \
#  --workload-pool={project-id}.svc.id.goog \
#--workload-identity-certificate-authority=//privateca.googleapis.com/projects/{project}/locations/{location}/certificateAuthorities/{name} \
#  --impersonate-service-account=testing-sa@{project}.iam.gserviceaccount.com \
#  --zone={location}
