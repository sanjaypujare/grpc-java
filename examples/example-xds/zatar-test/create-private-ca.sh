#!/bin/bash -x

CA_ZONE=us-central1
PROJECT=bct-staging-td-gke-consumer
CA_NAME=pkcs2-ca-staging-nov30

# Create CA: note we don’t use --tier DevOps because of the KMS option
/google/data/ro/teams/cloud-sdk/gcloud beta privateca roots create ${CA_NAME} \
  --location=${CA_ZONE} \
  --subject "CN=Sanjay Staging Production Root CA, O=Sanjay Staging LLC"
