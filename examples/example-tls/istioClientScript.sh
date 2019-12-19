#!/bin/sh

for i in `seq 1 ${COUNT:=1}`
do
    echo Running test number $i
    #/build/install/example-tls/bin/hello-world-tls-client demo-server 8000
done
