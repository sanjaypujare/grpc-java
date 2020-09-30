#!/bin/bash -x

IMAGENAME=zatar-grpc-xds
TAG=1.02
PROJECTID=meshca-gke-test

echo Building ${IMAGENAME}:${TAG}

docker build --no-cache -t zatar/${IMAGENAME}:${TAG} -f Dockerfile-xds.common .

docker tag zatar/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
