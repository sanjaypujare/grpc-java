#!/bin/bash -x

IMAGENAME=fake-bootstrap-generator
TAG=0.01
PROJECTID=meshca-gke-test

echo Building ${IMAGENAME}:${TAG}

docker build --no-cache -t ug-example/${IMAGENAME}:${TAG} -f ug-example/Dockerfile.init-container .

docker tag ug-example/${IMAGENAME}:${TAG} gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}

docker push gcr.io/${PROJECTID}/${IMAGENAME}:${TAG}
