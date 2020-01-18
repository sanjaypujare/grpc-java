#!/bin/bash -x

IMAGENAME=grpc-sds-server
TAG=1.01
PROJECTID=grpc-sds-testing

echo Building ${IMAGENAME}:${TAG}

docker build --no-cache -t mgrpc/${IMAGENAME}:${TAG} -f Dockerfile_istio.server .

docker tag mgrpc/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
