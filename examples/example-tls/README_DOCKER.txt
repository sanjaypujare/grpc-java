Very raw instructions for running the Docker experiment:

- Note that the images created on your machine don't survive machine reboots,
  so if you have rebooted this machine, recreate both images by running
  buildServer.sh and buildClient.sh

- Before running the build*.sh commands of course create the crypto as 
  described in ./README.md. Also copy the /tmp/sslcert/ to ./tmp/sslcert/
  
- Run the server docker image as

docker run -p 54440:54440 -d mgrpc/proto1server:1.0

  -p for exposing the port to the host
  -d for daemon mode

- Running "docker ps" will show the proto1server running in daemon mode

- Run the client docker as

on MacOS:

docker run --add-host=localhost:`ipconfig getifaddr en0` -it mgrpc/proto1client:1.0

on gLinux

docker run --add-host=localhost:`hostname --ip-address` -it mgrpc/proto1client:1.0

  -it to run in interactive mode
  --add-host to tell container to map localhost to local en0 IP address

Note the command "ipconfig getifaddr en0" on MacOS or "hostname --ip-address" on gLinux
gets you the local IP address (on en0 or such NIC)

- Inside the shell of proto1client, run this command

/build/install/example-tls/bin/hello-world-tls-client localhost 54440 /tmp/sslcert/ca.crt /tmp/sslcert/client.crt /tmp/sslcert/client.pem

  You should see a successful response:
Mar 18, 2019 9:32:05 PM io.grpc.examples.helloworldtls.HelloWorldClientTls greet
INFO: Will try to greet localhost ...
Mar 18, 2019 9:32:05 PM io.grpc.examples.helloworldtls.HelloWorldClientTls greet
INFO: Greeting: Hello localhost
