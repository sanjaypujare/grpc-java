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

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLException;

/**
 * A simple client that requests a greeting from the {@link HelloWorldServerTls} with TLS.
 */
public class HelloWorldClientTls {
    private static final Logger logger = Logger.getLogger(HelloWorldClientTls.class.getName());

    private final ManagedChannel channel;
    private final GreeterGrpc.GreeterBlockingStub blockingStub;
    private final String localIpAddress;

    private static SslContext buildSslContext(String trustCertCollectionFilePath,
                                              String clientCertChainFilePath,
                                              String clientPrivateKeyFilePath) throws Exception {
        SslContextBuilder builder = GrpcSslContexts.forClient();
        if (trustCertCollectionFilePath != null) {
            // new File(trustCertCollectionFilePath)
            builder.trustManager(new io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory(
                toX509Certificates(trustCertCollectionFilePath), null));
        }
        if (clientCertChainFilePath != null && clientPrivateKeyFilePath != null) {
            builder.keyManager(new File(clientCertChainFilePath), new File(clientPrivateKeyFilePath));
        }
        return builder.build();
    }

    static java.security.cert.X509Certificate[] toX509Certificates(String file) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
            java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis)) {
            java.util.Collection<? extends java.security.cert.Certificate> certs =
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(bis);
            return certs.toArray(new java.security.cert.X509Certificate[0]);
        }
    }

    /**
     * Construct client connecting to HelloWorld server at {@code host:port}.
     */
    public HelloWorldClientTls(String host,
                               int port,
                               SslContext sslContext,
                               String overrideAuthority) throws Exception {

        this(NettyChannelBuilder.forAddress(host, port)
                .overrideAuthority(overrideAuthority)  /* Only for using provided test certs. */
                .sslContext(sslContext)
                .build());
    }

    /**
     * Construct client for accessing RouteGuide server using the existing channel.
     */
    HelloWorldClientTls(ManagedChannel channel) throws Exception {
        this.channel = channel;
        blockingStub = GreeterGrpc.newBlockingStub(channel);
        this.localIpAddress = InetAddress.getLocalHost().getHostAddress();
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Say hello to server.
     */
    public void greet(String name) {
        logger.info("IMAGE_DATE=" + System.getenv("IMAGE_DATE"));
        logger.info("Will try to greet " + name + " ...");
        HelloRequest request = HelloRequest.newBuilder().setName(localIpAddress).build();
        HelloReply response;
        try {
            response = blockingStub.sayHello(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return;
        }
        logger.info("Greeting: " + response.getMessage());
    }

    /**
     * Greet server. If provided, the first element of {@code args} is the name to use in the
     * greeting.
     */
    public static void main(String[] args) throws Exception {

        if (args.length < 2 || args.length == 4 || args.length > 6) {
            System.out.println("USAGE: HelloWorldClientTls host port [trustCertCollectionFilePath " +
                    "[clientCertChainFilePath clientPrivateKeyFilePath] [overrideAuthority]]\n  Note: clientCertChainFilePath and " +
                    "clientPrivateKeyFilePath are only needed if mutual auth is desired.");
            System.exit(0);
        }

        HelloWorldClientTls client;
        switch (args.length) {
            case 2:
                /* Use default CA. Only for real server certificates. */
                client = new HelloWorldClientTls(args[0], Integer.parseInt(args[1]),
                        buildSslContext(null, null, null), "foo.test.google.fr");
                break;
            case 3:
                client = new HelloWorldClientTls(args[0], Integer.parseInt(args[1]),
                        buildSslContext(args[2], null, null), "foo.test.google.fr");
                break;
            default:
                client = new HelloWorldClientTls(args[0], Integer.parseInt(args[1]),
                        buildSslContext(args[2], args[3], args[4]), args[5]);
        }

        try {
            client.greet(args[0]);
        } finally {
            client.shutdown();
        }
    }
}
