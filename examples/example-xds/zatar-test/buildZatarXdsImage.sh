#!/bin/bash -x

IMAGENAME=psms-grpc-xds
TAG=1.08
PROJECTID=bct-staging-td-gke-consumer

echo Building ${IMAGENAME}:${TAG}

docker build --build-arg builddate="$(date)" --no-cache -t zatar/${IMAGENAME}:${TAG} -f zatar-test/Dockerfile-xds.common .

docker tag zatar/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
