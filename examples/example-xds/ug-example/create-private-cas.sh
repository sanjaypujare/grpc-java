#!/bin/bash -x

ROOT_CA_NAME=zatar-root-apr21
ROOT_CA_LOCATION=us-west1
SUBORDINATE_CA_NAME=zatar-subord-apr21
SUBORDINATE_CA_LOCATION=us-west1

#gcloud beta privateca roots create ${ROOT_CA_NAME} \
#  --subject "CN=${ROOT_CA_NAME}, O=${ROOT_CA_NAME}" \
#  --key-algorithm "rsa-pkcs1-2048-sha256" \
#  --max-chain-length=1 \
#  --location ${ROOT_CA_LOCATION} \
#  --tier enterprise

#Create a subordinate CA:
gcloud beta privateca subordinates create ${SUBORDINATE_CA_NAME} \
  --issuer ${ROOT_CA_NAME} \
  --issuer-location ${ROOT_CA_LOCATION} \
  --subject "CN=${SUBORDINATE_CA_NAME}, O=sub-ca-org" \
  --key-algorithm "rsa-pkcs1-2048-sha256" \
  --location ${SUBORDINATE_CA_LOCATION} \
  --tier devops
