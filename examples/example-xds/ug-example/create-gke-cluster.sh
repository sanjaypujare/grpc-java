#!/bin/bash -x

ME=sanjaypujare@google.com
PROJECT=meshca-gke-test
SA_NAME=me-sa-name    # Needs to be at least 6 characters.
CLUSTER_NAME=zatar-neg-dec14
CLUSTER_ZONE=us-west1-a
CA_NAME=pkcs2-ca
CA_ZONE=us-west1
SA=${SA_NAME}@${PROJECT}.iam.gserviceaccount.com
CA_URL=//privateca.googleapis.com/projects/${PROJECT}/locations/${CA_ZONE}/certificateAuthorities/${CA_NAME}

# To create a cluster with workload identity and managed SPIFFE certs enabled
gcloud beta container clusters create ${CLUSTER_NAME} --cluster-version=1.18.12-gke.1200 \
  --image-type=cos_containerd --release-channel=rapid --workload-pool=${PROJECT}.svc.id.goog \
  --workload-identity-certificate-authority=${CA_URL} --impersonate-service-account=${SA} \
  --zone=${CLUSTER_ZONE} --scopes=cloud-platform  --enable-ip-alias \
  --tags=allow-health-checks --workload-metadata=GCE_METADATA

gcloud container clusters get-credentials ${CLUSTER_NAME} --zone=${CLUSTER_ZONE}

# To update a cluster to add workload identity and managed SPIFFE certs. Note that the cluster must be version 1.18 or above in the rapid channel.
#gcloud alpha container clusters update existing-cluster \
#  --workload-pool={project-id}.svc.id.goog \
#--workload-identity-certificate-authority=//privateca.googleapis.com/projects/{project}/locations/{location}/certificateAuthorities/{name} \
#  --impersonate-service-account=testing-sa@{project}.iam.gserviceaccount.com \
#  --zone={location}
