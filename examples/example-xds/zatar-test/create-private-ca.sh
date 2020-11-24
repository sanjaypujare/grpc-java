#!/bin/bash -x

CA_ZONE=us-central1
PROJECT=bct-staging-td-gke-consumer
CA_NAME=pkcs2-ca-staging

# Create private key
/google/data/ro/teams/cloud-sdk/gcloud kms keyrings create kr2 --location ${CA_ZONE} --project ${PROJECT}
/google/data/ro/teams/cloud-sdk/gcloud kms keys create pkcs2-key --keyring kr2 --location ${CA_ZONE} --default-algorithm rsa-sign-pkcs1-4096-sha256 --purpose asymmetric-signing --project ${PROJECT}

# Create CA: note we don’t use --tier DevOps because of the KMS option
/google/data/ro/teams/cloud-sdk/gcloud beta privateca roots create ${CA_NAME} \
  --location=${CA_ZONE} \
  --kms-key-version  projects/${PROJECT}/locations/${CA_ZONE}/keyRings/kr2/cryptoKeys/pkcs2-key/cryptoKeyVersions/1 \
  --subject "CN=Sanjay Staging Production Root CA, O=Sanjay Staging LLC"
