#!/bin/bash -x

# TODO: source from a common file or even better use a common script for client and server and just pass arg for client or server
IMAGENAME=proto1_istio_client
TAG=0.5
PROJECTID=mgrpc-prototype-gke

echo Building ${IMAGENAME}:${TAG}

docker build -t mgrpc/${IMAGENAME}:${TAG} -f Dockerfile_istio.client .

docker tag mgrpc/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
