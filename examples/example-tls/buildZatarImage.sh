#!/bin/bash -x

IMAGENAME=zatar-grpc-common
TAG=1.00
PROJECTID=meshca-gke-test

echo Building ${IMAGENAME}:${TAG}

docker build --no-cache -t zatar/${IMAGENAME}:${TAG} -f Dockerfile.common .

docker tag zatar/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
