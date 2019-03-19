#!/bin/bash

IMAGENAME=proto1_istio_server
TAG=0.5
PROJECTID=mgrpc-prototype-gke

docker build -t mgrpc/${IMAGENAME}:${TAG} -f Dockerfile_istio.server .

docker tag mgrpc/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
