/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.helloworldtls;

import io.grpc.Server;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.sds.XdsServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server with TLS enabled.
 */
public class HelloWorldServerTls {
    private static final Logger logger = Logger.getLogger(HelloWorldServerTls.class.getName());

    private Server server;

    private final String host;
    private final int port;
    private String listenerResourceName;
    private String localIpAddress;

    public HelloWorldServerTls(String host, int port) throws UnknownHostException {
        this.host = host;
        this.port = port;
        this.localIpAddress = localIpAddress = InetAddress.getLocalHost().getHostAddress();
    }

    private void start() throws IOException {
        server = XdsServerBuilder.forPort(port)
                 .addService(new GreeterImpl(localIpAddress))
                 .listenerResourceName(listenerResourceName)
                 .build()
                 .start();
        logger.info("Server started, listening on " + localIpAddress + ":" + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                HelloWorldServerTls.this.stop();
                System.err.println("*** server shut down");
            }
        });
    }

    private void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main launches the server from the command line.
     */
    public static void main(String[] args) throws IOException, InterruptedException {

        if (args.length < 2) {
            System.out.println(
                    "USAGE: HelloWorldServerTls host port [listener-resource-name]\n" +
                    "  Note: No need to supply certs files.");
            System.exit(0);
        }

        final HelloWorldServerTls server = new HelloWorldServerTls(args[0],
                Integer.parseInt(args[1]));
        if (args.length > 2) {
            server.listenerResourceName = args[2];
        }
        server.start();
        server.blockUntilShutdown();
    }

    static class GreeterImpl extends GreeterGrpc.GreeterImplBase {
        private final String myIpAddress;

        GreeterImpl(String localIpAddress) {
            this.myIpAddress = localIpAddress;
        }

        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()
                + " from IP-address " + myIpAddress).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
}
