#!/bin/sh

for i in `seq 1 ${COUNT:=5}`
do
    /build/install/example-tls/bin/hello-world-tls-client localhost 54440 /tmp/sslcert/ca.crt /tmp/sslcert/client.crt /tmp/sslcert/client.pem
done
