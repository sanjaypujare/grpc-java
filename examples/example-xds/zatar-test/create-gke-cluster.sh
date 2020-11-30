#!/bin/bash -x

PROJECT=bct-staging-td-gke-consumer
CLUSTER_NAME=zatar-staging-nov30
CLUSTER_ZONE=us-west1-pj1
CA_NAME=pkcs2-ca-staging-nov30
CA_ZONE=us-central1
CA_URL=//privateca.googleapis.com/projects/${PROJECT}/locations/${CA_ZONE}/certificateAuthorities/${CA_NAME}

# To create a cluster with workload identity and managed SPIFFE certs enabled
/google/data/ro/teams/cloud-sdk/gcloud beta container clusters create ${CLUSTER_NAME} \
  --cluster-version=1.18.10-gke.1500 --image-type=cos_containerd   --release-channel=rapid \
  --workload-pool=${PROJECT}.svc.id.goog   --workload-identity-certificate-authority=${CA_URL} \
  --zone=${CLUSTER_ZONE} --scopes=cloud-platform   --enable-ip-alias --workload-metadata=GCE_METADATA

/google/data/ro/teams/cloud-sdk/gcloud container clusters get-credentials ${CLUSTER_NAME} --zone=${CLUSTER_ZONE}

# To update a cluster to add workload identity and managed SPIFFE certs. Note that the cluster must be version 1.18 or above in the rapid channel.
#gcloud alpha container clusters update existing-cluster \
#  --workload-pool={project-id}.svc.id.goog \
#--workload-identity-certificate-authority=//privateca.googleapis.com/projects/{project}/locations/{location}/certificateAuthorities/{name} \
#  --impersonate-service-account=testing-sa@{project}.iam.gserviceaccount.com \
#  --zone={location}
