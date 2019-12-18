#!/bin/bash -x

IMAGENAME=grpc-sds-server
TAG=0.5
PROJECTID=grpc-sds-testing

echo Building ${IMAGENAME}:${TAG}

docker build -t mgrpc/${IMAGENAME}:${TAG} -f Dockerfile_istio.server .

docker tag mgrpc/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
