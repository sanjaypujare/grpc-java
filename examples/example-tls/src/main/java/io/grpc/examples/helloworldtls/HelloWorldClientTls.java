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
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.xds.tls.SdsProtocolNegotiators;
import java.io.FileNotFoundException;
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

    /**
     * Construct client connecting to HelloWorld server at {@code host:port}.
     */
    public HelloWorldClientTls(String host,
                               int port,
                      String trustCertCollectionFilePath,
        String clientCertChainFilePath,
        String clientPrivateKeyFilePath ) throws SSLException, FileNotFoundException {

      System.out.println("building SdsProtocolNegotiators.Cfg here");

      SdsProtocolNegotiators.Cfg myCfg = new SdsProtocolNegotiators.Cfg();

      myCfg.keyCertChain = clientCertChainFilePath;
      myCfg.key = clientPrivateKeyFilePath;
      myCfg.trustChain = trustCertCollectionFilePath;


      NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port)
          .overrideAuthority("foo.test.google.fr");  /* Only for using provided test certs. */

      SdsProtocolNegotiators.setProtocolNegotiatorFactory(
          builder,
          new SdsProtocolNegotiators.ClientSdsProtocolNegotiatorFactory(myCfg));

      ManagedChannel channel =  builder
                .build();
      this.channel = channel;
      blockingStub = GreeterGrpc.newBlockingStub(channel);
    }

    /**
     * Construct client for accessing RouteGuide server using the existing channel.
     */
    HelloWorldClientTls(ManagedChannel channel) {
        this.channel = channel;
        blockingStub = GreeterGrpc.newBlockingStub(channel);
    }

    public void shutdown() throws InterruptedException {
      System.out.println("calling channel shutdown.....");
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        // the following wait (not sure how much needed) causes ProtocolNegotiator.close to be called
      // without the wait the close is not called.
      System.out.println("waiting 100L...");
      Thread.sleep(100L);
    }

    /**
     * Say hello to server.
     */
    public void greet(String name) {
        logger.info("Will try to greet " + name + " ...");
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();
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
                    "[clientCertChainFilePath clientPrivateKeyFilePath]] count\n  Note: clientCertChainFilePath and " +
                    "clientPrivateKeyFilePath are only needed if mutual auth is desired. call is made count times");
            System.exit(0);
        }

        HelloWorldClientTls client;
        switch (args.length) {

            default:
                client = new HelloWorldClientTls(args[0], Integer.parseInt(args[1]),
                        args[2], args[3], args[4]);
        }

        int count = 1;
        if (args.length > 5) {
        	count = Integer.parseInt(args[5]);
        }
        
        try {
        	for (int i = 0; i < count; i++) {
        		client.greet(args[0]);
        		Thread.sleep(1500L);
        	}
        } finally {
            client.shutdown();
        }
    }
}
