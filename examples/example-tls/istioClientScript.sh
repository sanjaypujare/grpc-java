#!/bin/sh

for i in `seq 1 ${COUNT:=50}`
do
    /build/install/example-tls/bin/hello-world-tls-client httpbin.default 54440 /etc/certs/root-cert.pem /etc/certs/cert-chain.pem /etc/certs/key.pem
done
