Installing the Envoy sidecar injector

Download and extract the Envoy sidecar injector.

wget https://storage.googleapis.com/traffic-director/td-sidecar-injector-xdsv3.tgz
tar -xzvf td-sidecar-injector-xdsv3.tgz
cd td-sidecar-injector-xdsv3

Edit specs/01-configmap.yaml:

Populate TRAFFICDIRECTOR_GCP_PROJECT_NUMBER with your project number.
Populate TRAFFICDIRECTOR_NETWORK_NAME with your network/VPC name. (typically "default")
Also add TRAFFICDIRECTOR_ACCESS_LOG_PATH to /etc/envoy/access.log if needed

Perform steps in https://cloud.google.com/traffic-director/docs/set-up-gke-pods-auto#configuring_tls_for_the_sidecar_injector

Perform steps in https://cloud.google.com/traffic-director/docs/set-up-gke-pods-auto#installing_the_sidecar_injector_to_your_cluster

After running ./ug-example/example-setup.sh :
kubectl label namespace example-grpc-client istio-injection=enabled


