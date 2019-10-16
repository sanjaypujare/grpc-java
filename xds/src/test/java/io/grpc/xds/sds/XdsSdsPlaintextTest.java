/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

/**
 * Unit tests for {@link XdsChannelBuilder} and {@link XdsServerBuilder}
 * for plaintext mode.
 */
@RunWith(JUnit4.class)
public class XdsSdsPlaintextTest {

  @Test
  public void buildsPlaintextClientServer() throws IOException {
    XdsServerBuilder serverBuilder
            = XdsServerBuilder.forPort(8080)
              .addService(new GreeterImpl())
              .tlsContext(null);
    Server server = serverBuilder.build();
    server.start();

    // now build the client channel
    XdsChannelBuilder builder = XdsChannelBuilder.forTarget("localhost:8080").tlsContext(null);
    ManagedChannel channel = builder.build();
    GreeterGrpc.GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(channel);
    String resp = greet("buddy", blockingStub);
    assertThat(resp).isEqualTo("Hello buddy");
  }

  /**
   * Say hello to server.
   */
  public String greet(String name, GreeterGrpc.GreeterBlockingStub blockingStub) {
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response = blockingStub.sayHello(request);
    return response.getMessage();
  }

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
