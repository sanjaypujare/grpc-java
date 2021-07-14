Installing the Envoy sidecar injector

Download and extract the Envoy sidecar injector.

wget https://storage.googleapis.com/traffic-director/td-sidecar-injector-xdsv3.tgz
tar -xzvf td-sidecar-injector-xdsv3.tgz
cd td-sidecar-injector-xdsv3

Edit specs/01-configmap.yaml (already done):

Populate TRAFFICDIRECTOR_GCP_PROJECT_NUMBER with your project number.
Populate TRAFFICDIRECTOR_NETWORK_NAME with your network/VPC name. (typically "default")
Also add TRAFFICDIRECTOR_ACCESS_LOG_PATH to /etc/envoy/access.log if needed

Perform steps in https://cloud.google.com/traffic-director/docs/set-up-gke-pods-auto#configuring_tls_for_the_sidecar_injector
(already done - so need to do it)



Perform steps in https://cloud.google.com/traffic-director/docs/set-up-gke-pods-auto#installing_the_sidecar_injector_to_your_cluster

After running ./ug-example/example-setup.sh :
kubectl label namespace example-grpc-client istio-injection=enabled

kubectl apply -f ./ug-example/gke-client-envoy-deployment.yaml

Create a DNS entry or modify /etc/hosts file to add

10.80.4.150     example-grpc-server

Assuming 10.80.4.150 is the IP address of your backend pod which is running the gRPC service

Enter the Envoy container using:
kubectl exec -it example-grpc-client-xxx-yyy -n example-grpc-client -c example-grpc-client  -- /bin/bash

build/install/example-xds/bin/xds-hello-world-client my-xds-client example-grpc-server:8000

--------------------------------------Debug Notes--------------------------------------
The Envoy container doesn't have useful commands. Use the application container.

curl localhost:15000/clusters
curl localhost:15000/admin_dump

kubectl logs example-grpc-client-79cd7798f7-pft56 -n example-grpc-client -c envoy
