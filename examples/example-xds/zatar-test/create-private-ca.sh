#!/bin/bash -x

CA_ZONE=us-central1
PROJECT=bct-gcestaging-guitar-grpc
CA_NAME=pkcs-ca-bct-staging-jan5

# Create private key
#/google/data/ro/teams/cloud-sdk/gcloud kms keyrings create kr2 --location ${CA_ZONE} --project ${PROJECT}
#/google/data/ro/teams/cloud-sdk/gcloud kms keys create pkcs2-key --keyring kr2 --location ${CA_ZONE} --default-algorithm rsa-sign-pkcs1-4096-sha256 --purpose asymmetric-signing --project ${PROJECT}

# Create CA: note we don’t use --tier DevOps because of the KMS option
/google/data/ro/teams/cloud-sdk/gcloud beta privateca roots create ${CA_NAME} \
  --location=${CA_ZONE} \
  --key-algorithm rsa-pkcs1-4096-sha256 \
  --subject "CN=Sanjay Bct Staging Jan5 Root CA, O=gRPC Staging LLC"
