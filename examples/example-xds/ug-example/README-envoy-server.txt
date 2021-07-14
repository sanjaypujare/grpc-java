After running ./ug-example/example-envoy-server-setup.sh :

Build the hello world example on your work machine (in ~/Projects/psm-security-test/grpc-java/examples):

./gradlew installDist

Create a tarball for the output
tar cvf ~/test/example-hw.tar build

Copy to the server container at /:
kubectl cp ~/test/example-hw.tar example-grpc-server-56bd5c8d49-7l44q:/ -n example-grpc-server -c example-grpc-server

Enter the server pod application container:
kubectl exec -it example-grpc-server-56bd5c8d49-7l44q -n example-grpc-server -c example-grpc-server -- /bin/bash

(rename old build directory if needed)
mv build build-old

(expand the tarball)
tar xvf example-hw.tar

./build/install/examples/bin/hello-world-server

Now deploy the client:


kubectl apply -f ./ug-example/gke-client-deployment.yaml


Enter the client container using:
kubectl exec -it example-grpc-client-xxx-yyy -n example-grpc-client -c example-grpc-client  -- /bin/bash

build/install/example-xds/bin/xds-hello-world-client --xds-creds my-xds-client xds:///example-grpc-server:8000

--------------------------------------Debug Notes--------------------------------------
The Envoy container doesn't have useful commands. Use the application container.

curl localhost:15000/clusters
curl localhost:15000/config_dump

kubectl logs example-grpc-client-79cd7798f7-pft56 -n example-grpc-client -c envoy
