#!/bin/bash -x

IMAGENAME=psms-grpc-xds
TAG=2.06
PROJECTID=meshca-gke-test

echo Building ${IMAGENAME}:${TAG}

docker build --build-arg builddate="$(date)" --no-cache -t zatar/${IMAGENAME}:${TAG} -f Dockerfile-xds.common .

docker tag zatar/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
