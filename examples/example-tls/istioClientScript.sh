#!/bin/sh

for i in `seq 1 ${COUNT:=2000}`
do
    echo Running test number $i
    /build/install/example-tls/bin/hello-world-tls-client grpc-sds-service 8000
done
